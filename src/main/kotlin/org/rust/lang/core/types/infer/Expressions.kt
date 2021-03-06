package org.rust.lang.core.types.infer

import org.rust.ide.utils.isNullOrEmpty
import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.ext.*
import org.rust.lang.core.resolve.*
import org.rust.lang.core.types.ty.*
import org.rust.lang.core.types.type

fun inferExpressionType(expr: RsExpr): Ty {
    return when (expr) {
        is RsPathExpr -> {
            val target = expr.path.reference.resolve() as? RsNamedElement
                ?: return TyUnknown

            inferDeclarationType(target)
        }

        is RsStructLiteral -> {
            val base = expr.path.reference.resolve()
            when (base) {
                is RsStructItem -> inferStructTypeParameters(expr, base)
                is RsEnumVariant -> inferEnumTypeParameters(expr, base)
                else -> TyUnknown
            }
        }

        is RsTupleExpr -> TyTuple(expr.exprList.map { it.type })
        is RsParenExpr -> expr.expr.type
        is RsUnitExpr -> TyUnit
        is RsCastExpr -> expr.typeReference.type

        is RsCallExpr -> {
            val fn = expr.expr
            if (fn is RsPathExpr) {
                val variant = fn.path.reference.resolve()
                when (variant) {
                    is RsEnumVariant -> return inferTupleEnumTypeParameters(expr, variant)
                    is RsStructItem -> return inferTupleStructTypeParameters(expr, variant)
                }
            }

            val calleeType = fn.type as? TyFunction ?: return TyUnknown
            calleeType.retType.substitute(mapTypeParameters(calleeType.paramTypes, expr.valueArgumentList.exprList))
        }

        is RsMethodCallExpr -> {
            val boundMethod = expr.reference.advancedResolve()
            val method = boundMethod?.element as? RsFunction ?: return TyUnknown
            val retType = (method.retType?.typeReference?.type ?: TyUnit).substitute(boundMethod.typeArguments)
            val methodType = method.type as? TyFunction ?: return retType
            // drop first element of paramTypes because it's `self` param
            // and it doesn't have value in `expr.valueArgumentList.exprList`
            retType.substitute(mapTypeParameters(methodType.paramTypes.drop(1), expr.valueArgumentList.exprList))
        }

        is RsFieldExpr -> {
            val boundField = expr.reference.advancedResolve()
            if (boundField == null) {
                val type = expr.expr.type as? TyTuple ?: return TyUnknown
                val fieldIndex = expr.fieldId.integerLiteral?.text?.toInt() ?: return TyUnknown
                return type.types.getOrElse(fieldIndex) { TyUnknown }
            }
            val field = boundField.element
            val raw = when (field) {
                is RsFieldDecl -> field.typeReference?.type
                is RsTupleFieldDecl -> field.typeReference.type
                else -> null
            } ?: TyUnknown
            raw.substitute(boundField.typeArguments)
        }

        is RsLitExpr -> {
            when (expr.kind) {
                is RsLiteralKind.Boolean -> TyBool
                is RsLiteralKind.Integer -> TyInteger.fromLiteral(expr.integerLiteral!!)
                is RsLiteralKind.Float -> TyFloat.fromLiteral(expr.floatLiteral!!)
                is RsLiteralKind.String -> TyReference(TyStr)
                is RsLiteralKind.Char -> TyChar
                null -> TyUnknown
            }
        }

        is RsBlockExpr -> expr.block.type
        is RsIfExpr -> if (expr.elseBranch == null) TyUnit else (expr.block?.type ?: TyUnknown)
    // TODO: handle break with value
        is RsWhileExpr, is RsLoopExpr, is RsForExpr -> return TyUnit

        is RsMatchExpr -> {
            expr.matchBody?.matchArmList.orEmpty().asSequence()
                .mapNotNull { it.expr?.type }
                .firstOrNull { it !is TyUnknown }
                ?: TyUnknown
        }

        is RsUnaryExpr -> {
            val base = expr.expr?.type ?: return TyUnknown
            return when (expr.operatorType) {
                UnaryOperator.REF -> TyReference(base, mutable = false)
                UnaryOperator.REF_MUT -> TyReference(base, mutable = true)
                UnaryOperator.DEREF -> when (base) {
                    is TyReference -> base.referenced
                    is TyPointer -> base.referenced
                    else -> TyUnknown
                }
                UnaryOperator.MINUS -> base
                UnaryOperator.NOT -> TyBool
                UnaryOperator.BOX -> TyUnknown
            }
        }

        is RsBinaryExpr -> {
            val op = expr.operatorType
            when (op) {
                is BoolOp -> TyBool
                is ArithmeticOp -> inferArithmeticBinaryExprType(expr, op)
                is AssignmentOp -> TyUnit
                else -> TyUnknown
            }
        }

        is RsTryExpr -> {
            // See RsMacroExpr where we handle the try! macro in a similar way
            val base = expr.expr.type

            if (isStdResult(base))
                (base as TyEnum).typeArguments.firstOrNull() ?: TyUnknown
            else
                TyUnknown
        }

        is RsArrayExpr -> inferArrayType(expr)

        is RsRangeExpr -> {
            val el = expr.exprList;
            val dot2 = expr.dotdot;
            val dot3 = expr.dotdotdot;

            val (rangeName, indexType) = when {
                dot2 != null && el.size == 0 -> "RangeFull" to null
                dot2 != null && el.size == 1 -> {
                    val e = el[0];
                    if (e.startOffsetInParent < dot2.startOffsetInParent) {
                        "RangeFrom" to e.type
                    } else {
                        "RangeTo" to e.type
                    }
                }
                dot2 != null && el.size == 2 -> {
                    "Range" to getMoreCompleteType(el[0].type, el[1].type)
                }
                dot3 != null && el.size == 1 -> {
                    val e = el[0];
                    if (e.startOffsetInParent < dot3.startOffsetInParent) {
                        return TyUnknown
                    } else {
                        "RangeToInclusive" to e.type
                    }
                }
                dot3 != null && el.size == 2 -> {
                    "RangeInclusive" to getMoreCompleteType(el[0].type, el[1].type)
                }

                else -> error("Unrecognized range expression")
            }

            findStdRange(rangeName, indexType, expr)
        }

        is RsIndexExpr -> {
            val containerType = expr.containerExpr?.type ?: return TyUnknown
            val indexType = expr.indexExpr?.type ?: return TyUnknown
            findIndexOutputType(expr.project, containerType, indexType)
        }

        is RsMacroExpr -> {
            if (expr.vecMacro != null) {
                val elements = expr.vecMacro!!.vecMacroArgs?.exprList ?: emptyList()
                var elementType: Ty = TyUnknown;
                for (e in elements) {
                    elementType = getMoreCompleteType(e.type, elementType);
                }

                findStdVec(elementType, expr)
            } else if (expr.logMacro != null) {
                TyUnit
            } else if (expr.macro != null) {
                val macro = expr.macro ?: return TyUnknown;
                when (macro) {
                    is RsTryMacro -> {
                        // See RsTryExpr where we handle the ? expression in a similar way
                        val base = macro.tryMacroArgs?.expr?.type ?: return TyUnknown;

                        if (isStdResult(base))
                            (base as TyEnum).typeArguments.firstOrNull() ?: TyUnknown
                        else
                            TyUnknown
                    }

                    is RsFormatLikeMacro -> {
                        val name = macro.macroInvocation.referenceName;

                        if (name == "format")
                            findStdString(expr)
                        else if (name == "format_args")
                            findStdArguments(expr)
                        else if (name == "write" || name == "writeln")
                            TyUnknown //Returns different types depending on first argument
                        else if (name == "panic" || name == "print" || name == "println")
                            TyUnit
                        else
                            TyUnknown
                    }
                    is RsAssertMacro,
                    is RsAssertEqMacro -> TyUnit

                    else -> TyUnknown
                }
            } else return TyUnknown;
        }

        else -> TyUnknown
    }
}

private fun getMoreCompleteType(t1: Ty, t2: Ty): Ty {
    if (t1 is TyUnknown)
        return t2;
    if (t1 is TyInteger && t2 is TyInteger && t1.isKindWeak)
        return t2;
    if (t1 is TyFloat && t2 is TyFloat && t1.isKindWeak)
        return t2;

    return t1
}

private fun inferArrayType(expr: RsArrayExpr): Ty {
    val (elementType, size) = if (expr.semicolon != null) {
        val elementType = expr.initializer?.type ?: return TySlice(TyUnknown)
        val size = calculateArraySize(expr.sizeExpr) ?: return TySlice(elementType)
        elementType to size
    } else {
        val elements = expr.arrayElements
        if (elements.isNullOrEmpty()) return TySlice(TyUnknown)

        var elementType: Ty = TyUnknown;
        // '!!' is safe here because we've just checked that elements isn't null
        for (e in elements!!) {
            elementType = getMoreCompleteType(e.type, elementType);
        }
        elementType to elements.size;
    }
    return TyArray(elementType, size)
}

private val RsBlock.type: Ty get() = expr?.type ?: TyUnit

private fun inferStructTypeParameters(o: RsStructLiteral, item: RsStructItem): Ty {
    val baseType = item.type
    if ((baseType as? TyStructOrEnumBase)?.typeArguments.isNullOrEmpty()) return baseType
    val argsMapping = item.blockFields?.let { inferTypeParametersForFields(o.structLiteralBody.structLiteralFieldList, it) } ?: emptyMap()
    return if (argsMapping.isEmpty()) baseType else baseType.substitute(argsMapping)
}

private fun inferEnumTypeParameters(o: RsStructLiteral, item: RsEnumVariant): Ty {
    val baseType = item.parentEnum.type
    if ((baseType as? TyStructOrEnumBase)?.typeArguments.isNullOrEmpty()) return baseType
    val argsMapping = item.blockFields?.let { inferTypeParametersForFields(o.structLiteralBody.structLiteralFieldList, it) } ?: emptyMap()
    return if (argsMapping.isEmpty()) baseType else baseType.substitute(argsMapping)
}

private fun inferTupleStructTypeParameters(o: RsCallExpr, item: RsStructItem): Ty {
    val baseType = item.type
    if ((baseType as? TyStructOrEnumBase)?.typeArguments.isNullOrEmpty()) return baseType
    val argsMapping = item.tupleFields?.let { inferTypeParametersForTuple(o.valueArgumentList.exprList, it) } ?: emptyMap()
    return if (argsMapping.isEmpty()) baseType else baseType.substitute(argsMapping)
}

private fun inferTupleEnumTypeParameters(o: RsCallExpr, item: RsEnumVariant): Ty {
    val baseType = item.parentEnum.type
    if ((baseType as? TyStructOrEnumBase)?.typeArguments.isNullOrEmpty()) return baseType
    val argsMapping = item.tupleFields?.let { inferTypeParametersForTuple(o.valueArgumentList.exprList, it) } ?: emptyMap()
    return if (argsMapping.isEmpty()) baseType else baseType.substitute(argsMapping)
}

private fun inferTypeParametersForFields(
    structLiteralFieldList: List<RsStructLiteralField>,
    fields: RsBlockFields
): Map<TyTypeParameter, Ty> {
    val argsMapping = mutableMapOf<TyTypeParameter, Ty>()
    val fieldTypes = fields.fieldDeclList
        .associate { it.identifier.text to (it.typeReference?.type ?: TyUnknown) }
    structLiteralFieldList.forEach { field ->
        field.expr?.let { expr -> addTypeMapping(argsMapping, fieldTypes[field.identifier.text], expr) }
    }
    return argsMapping
}

private fun inferTypeParametersForTuple(
    tupleExprs: List<RsExpr>,
    tupleFields: RsTupleFields
): Map<TyTypeParameter, Ty> {
    return mapTypeParameters(tupleFields.tupleFieldDeclList.map { it.typeReference.type }, tupleExprs)
}

private fun inferArithmeticBinaryExprType(expr: RsBinaryExpr, op: ArithmeticOp): Ty {
    val lhsType = expr.left.type
    val rhsType = expr.right?.type ?: TyUnknown
    return findArithmeticBinaryExprOutputType(expr.project, lhsType, rhsType, op)
}

private fun mapTypeParameters(
    argDefs: Iterable<Ty>,
    argExprs: Iterable<RsExpr>
): Map<TyTypeParameter, Ty> {
    val argsMapping = mutableMapOf<TyTypeParameter, Ty>()
    argExprs.zip(argDefs).forEach { (expr, type) -> addTypeMapping(argsMapping, type, expr) }
    return argsMapping
}

private fun addTypeMapping(argsMapping: TypeMapping, fieldType: Ty?, expr: RsExpr) =
    fieldType?.canUnifyWith(expr.type, expr.project, argsMapping)

/**
 * Remap type parameters between type declaration and an impl block.
 *
 * Think about the following example:
 * ```
 * struct Flip<A, B> { ... }
 * impl<X, Y> Flip<Y, X> { ... }
 * ```
 */
fun RsImplItem.remapTypeParameters(
    map: Map<TyTypeParameter, Ty>
): Map<TyTypeParameter, Ty> =
    typeReference?.type?.typeParameterValues.orEmpty()
        .mapNotNull {
            val (structParam, structType) = it
            if (structType is TyTypeParameter) {
                val implType = map[structParam] ?: return@mapNotNull null
                structType to implType
            } else {
                null
            }
        }.toMap()

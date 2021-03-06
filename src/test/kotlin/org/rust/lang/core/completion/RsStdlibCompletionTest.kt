package org.rust.lang.core.completion

import com.intellij.testFramework.LightProjectDescriptor

class RsStdlibCompletionTest : RsCompletionTestBase() {
    override fun getProjectDescriptor(): LightProjectDescriptor = WithStdlibRustProjectDescriptor

    fun testPrelude() = checkSingleCompletion("drop()", """
        fn main() {
            dr/*caret*/
        }
    """)

    fun testPreludeVisibility() = checkNoCompletion("""
        mod m {}
        fn main() {
            m::dr/*caret*/
        }
    """)

    fun testIter() = checkSingleCompletion("iter_mut()", """
        fn main() {
            let vec: Vec<i32> = Vec::new();
            let iter = vec.iter_m/*caret*/
        }
    """)
}


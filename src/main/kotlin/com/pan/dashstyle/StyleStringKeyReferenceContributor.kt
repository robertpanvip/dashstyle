package com.pan.dashstyle
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.patterns.PlatformPatterns
import com.intellij.lang.javascript.psi.JSLiteralExpression

class StyleStringKeyReferenceContributor : PsiReferenceContributor() {
    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {

        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(JSLiteralExpression::class.java),
            StyleStringKeyReferenceProvider()
        )
    }
}

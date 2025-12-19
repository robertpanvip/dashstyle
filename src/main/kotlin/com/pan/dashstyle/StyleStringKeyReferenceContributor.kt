package com.pan.dashstyle

import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.patterns.PlatformPatterns
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.openapi.diagnostic.Logger

class StyleStringKeyReferenceContributor : PsiReferenceContributor() {
    private val LOG = Logger.getInstance(StyleStringKeyReferenceContributor::class.java)
    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        //LOG.info("StyleStringKeyReferenceContributor: 注册 Reference Provider")  // ← 这行一定能看到
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(JSLiteralExpression::class.java),
            StyleStringKeyReferenceProvider()
        )
    }
}

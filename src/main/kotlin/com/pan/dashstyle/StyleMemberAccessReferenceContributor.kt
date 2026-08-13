package com.pan.dashstyle

import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceRegistrar

class StyleMemberAccessReferenceContributor : PsiReferenceContributor() {
    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        // styles.xxx — qualifier + memberName 形式
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(JSReferenceExpression::class.java),
            StyleMemberAccessReferenceProvider()
        )
    }
}

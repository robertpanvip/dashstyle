package com.pan.dashstyle.reference

import com.pan.dashstyle.inspection.*
import com.pan.dashstyle.action.*
import com.pan.dashstyle.support.*
import com.pan.dashstyle.annotator.*

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

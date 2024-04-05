package com.alistar.kotlinclassmappergenerator

import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.ProjectScope
import org.jetbrains.kotlin.asJava.classes.KtUltraLightClass
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.types.TypeProjection
import org.jetbrains.kotlinx.serialization.compiler.backend.common.serialName

fun TypeProjection.getTypeInfo(
    project: Project
): Pair<KtClass?, String> {
    val typeArgumentSerialName = type.serialName()

    val typeArgumentNewPsiClass = JavaPsiFacade.getInstance(project)
        .findClass(typeArgumentSerialName, ProjectScope.getAllScope(project))

    val typeArgumentKtUltraLightClass = typeArgumentNewPsiClass as? KtUltraLightClass
    val typeArgumentNestedClass = typeArgumentKtUltraLightClass?.kotlinOrigin as? KtClass
    val typeArgumentNestedClassName = typeArgumentNestedClass?.fqName?.shortName()?.asString() ?: ""
    return Pair(typeArgumentNestedClass, typeArgumentNestedClassName)
}

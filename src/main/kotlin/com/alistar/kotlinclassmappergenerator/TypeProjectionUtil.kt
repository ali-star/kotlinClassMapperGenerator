package com.alistar.kotlinclassmappergenerator

import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.ProjectScope
import org.jetbrains.kotlin.asJava.classes.KtUltraLightClass
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.types.TypeProjection
import org.jetbrains.kotlinx.serialization.compiler.backend.common.serialName

/**
 * Presents a simplified type information about a TypeProjection that is contained in the project.
 * @param ktClass the kotlin class that is contained in the project
 * @param ktClassName the name of the kotlin class
 */
data class TypeInfo(
    val ktClass: KtClass,
    val ktClassName: String,
)

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

/**
 * Presents a simplified type information about a TypeProjection that is contained in the project.
 * When a type is not being contained in the project the function will return null.
 * @param project need to check if the type is a kt class, and it is contained in the project.
 */
fun TypeProjection.localTypeInfo(
    project: Project
): TypeInfo? {
    val typeSerialName = type.serialName()
    val typePsiClass = JavaPsiFacade.getInstance(project).findClass(typeSerialName, ProjectScope.getAllScope(project))
    val typeKtUltraLightClass = typePsiClass as? KtUltraLightClass
    val typeNestedClass = typeKtUltraLightClass?.kotlinOrigin as? KtClass ?: return null
    val typeNestedClassName = typeNestedClass.fqName?.shortName()?.asString() ?: ""
    return TypeInfo(typeNestedClass, typeNestedClassName)
}

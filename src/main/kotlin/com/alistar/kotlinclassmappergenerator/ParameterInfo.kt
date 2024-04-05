package com.alistar.kotlinclassmappergenerator

import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.ProjectScope
import org.jetbrains.kotlin.asJava.classes.KtUltraLightClass
import org.jetbrains.kotlin.idea.base.utils.fqname.fqName
import org.jetbrains.kotlin.idea.caches.resolve.analyze
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.typeUtil.supertypes
import org.jetbrains.kotlinx.serialization.compiler.backend.common.serialName
import kotlin.reflect.KClass

data class ParameterInfo(
    val type: KotlinType,
    val name: String?,
    val ultraLightClass: KtUltraLightClass?,
    val ktClass: KtClass?,
    val ktClassName: String?
)

fun KtParameter.getInfo(): ParameterInfo {
    val name = fqName?.shortName()?.asString()
    val typeReference = typeReference
    val bindingContext = typeReference?.analyze()
    val type = bindingContext?.get(BindingContext.TYPE, typeReference)
    val aSerialName = type?.serialName()

    val newPsiClass = JavaPsiFacade.getInstance(project)
        .findClass(aSerialName!!, ProjectScope.getAllScope(project))

    val ktUltraLightClass = newPsiClass as? KtUltraLightClass
    val nestedClass = ktUltraLightClass?.kotlinOrigin as? KtClass
    val nestedClassName = nestedClass?.fqName?.shortName()?.asString() ?: ""
    return ParameterInfo(
        type = type,
        name = name,
        ultraLightClass = ktUltraLightClass,
        ktClass = nestedClass,
        ktClassName = nestedClassName
    )
}

fun ParameterInfo.hasSuperType(clazz: KClass<*>) = type.fqName?.asString() == clazz.qualifiedName || type.supertypes().find {
    it.fqName?.asString() == clazz.qualifiedName
} != null

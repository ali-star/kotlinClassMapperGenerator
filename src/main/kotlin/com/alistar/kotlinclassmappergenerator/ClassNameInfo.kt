package com.alistar.kotlinclassmappergenerator

import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPsiFactory

data class ClassNameInfo(
    val className: String,
    val mappedClassName: String,
)

fun KtClass.getClassNameInfo(
    packageName: String,
    classSuffix: String,
): ClassNameInfo {
    val ktClassPackageName = containingKtFile.packageFqName.asString()

    val ktClassName = if (ktClassPackageName != packageName) {
        fqName?.asString()
    } else {
        fqName?.asString()?.replace("$packageName.", "") ?: ""
    }

    val mappedKtClassName = ktClassName
        ?.replace("$ktClassPackageName.", "")
        ?.replace(".", "$classSuffix.")?.let {
            "$it$classSuffix"
        }

    return ClassNameInfo(
        className = fqName?.asString()?.substringAfterLast(".") ?: "",
        mappedClassName = mappedKtClassName ?: ""
    )
}

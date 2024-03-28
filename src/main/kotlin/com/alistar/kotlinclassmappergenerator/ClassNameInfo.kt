package com.alistar.kotlinclassmappergenerator

import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.resolve.ImportPath

data class ClassNameInfo(
    val className: String,
    val mappedClassName: String,
)

fun KtClass.getClassNameInfo(
    packageName: String,
    classSuffix: String,
    file: KtFile,
    psiFactory: KtPsiFactory,
): ClassNameInfo {
    val ktClassPackageName = containingKtFile.packageFqName.asString()

    val ktClassName = if (ktClassPackageName != packageName) {
        fqName?.asString()
    } else {
        fqName?.asString()?.replace("$packageName.", "") ?: ""
    }

    ktClassName.takeIf { !it.isNullOrEmpty() && it.contains(".") }?.let {
        file.importList?.add(psiFactory.createImportDirective(ImportPath.fromString(it)))
    }
    val mappedKtClassName = ktClassName
        ?.replace("$ktClassPackageName.", "")
        ?.replace(".", "$classSuffix.")?.let {
            "$packageName.$it$classSuffix"
        }
    val mappedKtClassName1 = ktClassName
        ?.replace("$ktClassPackageName.", "")
        ?.replace(".", "$classSuffix.")?.let {
            "$it$classSuffix"
        }
    mappedKtClassName.takeIf { !it.isNullOrEmpty() && it.contains(".") }?.let {
        file.importList?.add(psiFactory.createImportDirective(ImportPath.fromString(it)))
    }
    return ClassNameInfo(
        className = fqName?.asString()?.substringAfterLast(".") ?: "",
        mappedClassName = mappedKtClassName1 ?: ""
    )
}

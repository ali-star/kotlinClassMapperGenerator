package com.alistar.kotlinclassmappergenerator

import com.intellij.psi.PsiElement
import com.intellij.psi.codeStyle.CodeStyleManager
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtImportDirective


fun PsiElement.reformat() {
    val ktFile = containingFile as KtFile
    removeUnusedImportDirectives(ktFile)
    CodeStyleManager.getInstance(project).reformat(this)
}

fun removeUnusedImportDirectives(file: KtFile) {
    val importDirectives = file.importDirectives
    val unusedImportDirectives = importDirectives.filter { it.isUsedImportDirective(file).not() }
    unusedImportDirectives.forEach { it.delete() }
}

fun KtImportDirective.isUsedImportDirective(file: KtFile): Boolean {
    if (importedFqName?.asString()?.endsWith("*") == true) return true

    val fileText = file.text
    val importShortName = importedFqName?.shortName()?.asString()
    val isUsedAsParameter = fileText.contains(": $importShortName,")
            || fileText.contains(": $importShortName?,")
    val isUsed = fileText.contains("$importShortName.")
    return isUsedAsParameter || isUsed
}

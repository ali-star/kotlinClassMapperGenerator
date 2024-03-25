package com.alistar.kotlinclassmappergenerator

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.psi.PsiFile
import org.jetbrains.kotlin.psi.KtClass

fun main() {
    println("Hello World")
}

class GenerateFileAction : AnAction("Kotlin Mapper Class") {

    private val mapperGenerator = MapperGeneratorV2()

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.getData(PlatformDataKeys.PROJECT) ?: return
        val psiElement = event.getData(PlatformDataKeys.PSI_ELEMENT)

        if (psiElement != null && psiElement is KtClass && psiElement.isData()) {
            val ktClass = psiElement as KtClass
            val packageName = ktClass.containingKtFile.packageFqName.asString()
            val directory = ktClass.containingKtFile.containingDirectory ?: return
            val psiClassName = ktClass.fqName?.shortName()?.asString() ?: ""
            NewMapperDialog(className = psiClassName).show { className, classSuffix, isRecursive ->
                mapperGenerator.generateClass(
                    ktClass = ktClass,
                    className = className,
                    classSuffix = classSuffix,
                    packageName = packageName,
                    directory = directory,
                    project = project,
                    isRecursive = isRecursive,
                )
                mapperGenerator.generateMapper(
                    ktClass = ktClass,
                    className = className,
                    classSuffix = classSuffix,
                    packageName = packageName,
                    directory = directory,
                    project = project,
                    isRecursive = isRecursive,
                )
            }
        } else {
            Notification.ErrorNotification().show(
                project = project,
                message = "Kotlin mapper generator only supports data classes",
            )
        }
    }
}

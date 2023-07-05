package com.alistar.kotlinclassmappergenerator

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.PlatformDataKeys
import org.jetbrains.kotlin.psi.KtClass

fun main() {
    println("Hello World")
}

class GenerateFileAction : AnAction("Kotlin Mapper Class") {

    private val mapperGenerator = MapperGenerator()

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.getData(PlatformDataKeys.PROJECT) ?: return
        val psiElement = event.getData(PlatformDataKeys.PSI_ELEMENT)

        if (psiElement != null && psiElement is KtClass && psiElement.isData()) {
            val ktClass = psiElement as KtClass
            val psiClassName = ktClass.fqName?.shortName()?.asString() ?: ""
            NewMapperDialog(className = psiClassName).show { className, classSuffix ->
                mapperGenerator.generateClass(
                    ktClass = ktClass,
                    className = className,
                    classSuffix = classSuffix,
                    project = project,
                )
                mapperGenerator.generateMapper(
                    ktClass = ktClass,
                    className = className,
                    classSuffix = classSuffix,
                    project = project,
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

package com.alistar.kotlinclassmappergenerator

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.search.ProjectScope
import org.jetbrains.kotlin.asJava.classes.KtUltraLightClass
import org.jetbrains.kotlin.idea.caches.resolve.analyze
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.types.isNullable
import org.jetbrains.kotlin.util.isAnnotated
import org.jetbrains.kotlinx.serialization.compiler.backend.common.serialName

class MapperGenerator {

    fun generateClass(
        ktClass: KtClass,
        className: String,
        classSuffix: String,
        project: Project,
    ) {
        val psiFileFactory = PsiFileFactory.getInstance(project)
        val psiFile = ktClass.containingKtFile as PsiFile
        val classStringBuilder = StringBuilder()
        val packageName = (psiFile as KtFile).packageFqName.asString()
        val primaryConstructorParameters = ktClass.primaryConstructorParameters

        addPackageName(classStringBuilder, packageName)
        addImports(psiFile, primaryConstructorParameters, classStringBuilder)

        classStringBuilder.append("data class $className$classSuffix (\n")

        primaryConstructorParameters.forEach { parameter ->
            val name = parameter.fqName?.shortName()?.asString()
            val text = parameter.text
            val typeReference = parameter.typeReference
            val bindingContext = typeReference?.analyze()
            val type = bindingContext?.get(BindingContext.TYPE, typeReference)
            val aSerialName = type?.serialName()

            val newPsiClass = JavaPsiFacade.getInstance(project)
                .findClass(aSerialName!!, ProjectScope.getAllScope(project))

            val ktUltraLightClass = newPsiClass as? KtUltraLightClass
            val nestedClass = ktUltraLightClass?.kotlinOrigin as? KtClass
            val nestedClassName = nestedClass?.fqName?.shortName()?.asString() ?: ""
            if (nestedClass != null) {
                if (nestedClass.isData() && nestedClass.containingKtFile.packageFqName.asString() == packageName) {
                    generateClass(
                        ktClass = nestedClass,
                        className = nestedClassName,
                        classSuffix = classSuffix,
                        project = project,
                    )
                }
                val defValue = parameter.defaultValue?.text
                val newFieldName = buildString {
                    append(if (parameter.isMutable) "var" else "val")
                    append(" ")
                    append(name + classSuffix)
                    append(": ")
                    append(type.toString().replace("?", "") + classSuffix)
                    if (defValue == "null") {
                        append("? = null")
                    } else if (defValue != null) {
                        append(replaceFirst(type.toString().toRegex(), type.toString() + classSuffix))
                    }
                }
                classStringBuilder.append("$newFieldName,\n")
            } else {
                if (parameter.isAnnotated) {
                    var textWithoutAnnotations = text
                    parameter.annotationEntries.forEach { annotation ->
                        textWithoutAnnotations = textWithoutAnnotations.replace(annotation.text, "")
                        textWithoutAnnotations.replace("\n", "")
                    }
                    classStringBuilder.append("$textWithoutAnnotations,\n")
                } else {
                    classStringBuilder.append("$text,\n")
                }
            }
            println(parameter)
        }

        classStringBuilder.append(")\n")

        val classStringBuilderText = classStringBuilder.toString()

        println(classStringBuilderText)

        val modelFile = psiFileFactory.createFileFromText(
            "$className$classSuffix.kt",
            KotlinFileType(),
            classStringBuilderText
        )

        val directory = (psiFile as PsiFile).containingDirectory

        val documentManager: PsiDocumentManager = PsiDocumentManager.getInstance(project)
        documentManager.commitAllDocuments()

        WriteCommandAction.runWriteCommandAction(project) {
            val addedModelFile = directory.add(modelFile)
            addedModelFile.reformat()
        }
        println(classStringBuilderText)
    }

    fun generateMapper(
        ktClass: KtClass,
        className: String,
        classSuffix: String,
        project: Project,
    ) {
        val psiFileFactory = PsiFileFactory.getInstance(project)
        val psiFile = ktClass.containingKtFile as PsiFile
        val mapperStringBuilder = StringBuilder()
        val packageName = (psiFile as KtFile).packageFqName.asString()

        addPackageName(mapperStringBuilder, packageName)
        mapperStringBuilder.append("fun ${ktClass.fqName?.shortName()}.mapTo$classSuffix(): $className$classSuffix = $className$classSuffix(\n")

        val primaryConstructorParameters = ktClass.primaryConstructorParameters
        primaryConstructorParameters.forEach { parameter ->
            val name = parameter.fqName?.shortName()?.asString()
            val typeReference = parameter.typeReference
            val bindingContext = typeReference?.analyze()
            val type = bindingContext?.get(BindingContext.TYPE, typeReference)
            val aSerialName = type?.serialName()

            val newPsiClass = JavaPsiFacade.getInstance(project)
                .findClass(aSerialName!!, ProjectScope.getAllScope(project))

            val ktUltraLightClass = newPsiClass as? KtUltraLightClass
            val nestedClass = ktUltraLightClass?.kotlinOrigin as? KtClass
            val nestedClassName = nestedClass?.fqName?.shortName()?.asString() ?: ""
            if (nestedClass != null) {
                if (nestedClass.isData() && nestedClass.containingKtFile.packageFqName.asString() == packageName) {
                    generateMapper(
                        ktClass = nestedClass,
                        className = nestedClassName,
                        classSuffix = classSuffix,
                        project = project,
                    )
                }
                mapperStringBuilder.append("${name + classSuffix} = $name" + (if (type.isNullable()) "?" else "") + ".mapTo$classSuffix(),\n")
            } else {
                mapperStringBuilder.append("$name = $name,\n")
            }
            println(parameter)
        }

        mapperStringBuilder.append(")\n")

        val mapperStringBuilderText = mapperStringBuilder.toString()

        val mapperFile = psiFileFactory.createFileFromText(
            className + "Mapper.kt",
            KotlinFileType(),
            mapperStringBuilderText
        )

        val directory = (psiFile as PsiFile).containingDirectory

        val documentManager: PsiDocumentManager = PsiDocumentManager.getInstance(project)
        documentManager.commitAllDocuments()

        WriteCommandAction.runWriteCommandAction(project) {
            val addedMapperFile = directory.add(mapperFile)
            addedMapperFile.reformat()
        }

        println(mapperStringBuilderText)
    }

    private fun addImports(
        psiFile: KtFile,
        primaryConstructorParameters: List<KtParameter>,
        classStringBuilder: StringBuilder
    ) {
        val imports = psiFile.importList?.text?.let { originalImports ->
            val originalList = originalImports.split("\n")
            val newList = ArrayList<String>()
            originalList.forEach { import ->
                var importIsAnnotation = false
                primaryConstructorParameters.forEach parametersLoop@{ parameter ->
                    if (parameter.isAnnotated) {
                        parameter.annotationEntries.forEach { annotation ->
                            val name = annotation.shortName?.asString()
                            if (import.endsWith(name ?: "---")) {
                                importIsAnnotation = true
                                return@parametersLoop
                            }
                        }
                    }
                }
                if (!importIsAnnotation) {
                    newList.add(import)
                }
            }
            newList.joinToString(separator = "\n") { it }
        }

        if (imports?.isNotEmpty() == true) {
            classStringBuilder.append("$imports\n\n")
        }
    }

    private fun addPackageName(classStringBuilder: StringBuilder, packageName: String) {
        classStringBuilder.append("package $packageName\n\n")
    }

    private fun PsiElement.reformat() {
        CodeStyleManager.getInstance(project).reformat(this)
    }
}
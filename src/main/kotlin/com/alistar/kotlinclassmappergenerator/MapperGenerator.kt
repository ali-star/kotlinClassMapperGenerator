package com.alistar.kotlinclassmappergenerator

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiDirectory
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
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.psiUtil.isPrivate
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.types.isNullable
import org.jetbrains.kotlin.util.isAnnotated
import org.jetbrains.kotlinx.serialization.compiler.backend.common.serialName

class MapperGenerator {

    fun generateClass(
        ktClass: KtClass,
        className: String,
        classSuffix: String,
        packageName: String,
        directory: PsiDirectory,
        project: Project,
    ) {
        val psiFileFactory = PsiFileFactory.getInstance(project)
        val psiFile = ktClass.containingKtFile as PsiFile
        val classStringBuilder = StringBuilder()
        val primaryConstructorParameters = ktClass.primaryConstructorParameters

        addPackageName(classStringBuilder, packageName)

        addImports(psiFile as KtFile, primaryConstructorParameters, classStringBuilder)

        classStringBuilder.append("data class $className$classSuffix (\n")

        primaryConstructorParameters.forEach parametersLoop@{ parameter ->
            if (parameter.isPrivate()) {
                return@parametersLoop
            }
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
                if (nestedClass.isData()) {
                    generateClass(
                        ktClass = nestedClass,
                        className = nestedClassName,
                        classSuffix = classSuffix,
                        directory = directory,
                        packageName = packageName,
                        project = project,
                    )
                }
                val defValue = parameter.defaultValue?.text
                val newFieldName = buildString {
                    append(if (parameter.isMutable) "var" else "val")
                    append(" ")
                    append(name + classSuffix)
                    append(": ")
                    append(type.toString().replace("?", ""))
                    if (nestedClass.isData()) {
                        append(classSuffix)
                    }
                    append(if (type.isNullable()) "?" else "")
                    if (defValue == "null") {
                        append(" = null")
                    }
                }
                classStringBuilder.append("$newFieldName,\n")
            } else {
                val defValue = parameter.defaultValue?.text
                val newFieldName = buildString {
                    append(if (parameter.isMutable) "var" else "val")
                    append(" ")
                    append(name)
                    append(": ")
                    append(type.toString())
                    if (defValue == "null") {
                        append(" = null")
                    }
                }
                classStringBuilder.append("$newFieldName,\n")
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
        packageName: String,
        directory: PsiDirectory,
        project: Project,
    ) {
        val psiFileFactory = PsiFileFactory.getInstance(project)
        val ktFile = ktClass.containingKtFile
        val mapperStringBuilder = StringBuilder()

        val classPackageName = ktFile.packageFqName.asString()

        addPackageName(mapperStringBuilder, packageName)

        if (packageName != classPackageName) {
            mapperStringBuilder.append("import $classPackageName.${ktClass.name}\n")
        }

        mapperStringBuilder.append(
            "fun ${ktClass.fqName?.shortName()}.mapTo$classSuffix(): $className$classSuffix = $className$classSuffix(\n"
        )

        val primaryConstructorParameters = ktClass.primaryConstructorParameters
        primaryConstructorParameters.forEach parametersLoop@{ parameter ->
            if (parameter.isPrivate()) {
                return@parametersLoop
            }
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
                if (nestedClass.isData()) {
                    generateMapper(
                        ktClass = nestedClass,
                        className = nestedClassName,
                        classSuffix = classSuffix,
                        packageName = packageName,
                        directory = directory,
                        project = project,
                    )
                }
                if (nestedClass.isData()) {
                    mapperStringBuilder.append(name + classSuffix)
                    mapperStringBuilder.append(" = ")
                    mapperStringBuilder.append(name + (if (type.isNullable()) "?" else "") + ".mapTo$classSuffix(),\n")
                } else {
                    mapperStringBuilder.append("${name + classSuffix} = $name,\n")
                }
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
            mapperStringBuilderText,
        )

        val documentManager: PsiDocumentManager = PsiDocumentManager.getInstance(project)
        documentManager.commitAllDocuments()

        WriteCommandAction.runWriteCommandAction(project) {
            val addedMapperFile = directory.add(mapperFile)
            addedMapperFile.reformat()
        }

        println(mapperStringBuilderText)
    }

    private fun addImports(
        ktFile: KtFile,
        primaryConstructorParameters: List<KtParameter>,
        classStringBuilder: StringBuilder
    ) {
        val newList = ArrayList<String>()
        val imports = ktFile.importList?.imports
        imports?.forEach { import ->
            val text = import.text
            var importIsAnnotation = false
            primaryConstructorParameters.forEach parametersLoop@{ parameter ->
                if (parameter.isAnnotated) {
                    parameter.annotationEntries.forEach { annotation ->
                        val name = annotation.shortName?.asString()
                        if (text.endsWith(name ?: "---")) {
                            importIsAnnotation = true
                            return@parametersLoop
                        }
                    }
                }
            }
            if (!importIsAnnotation) {
                newList.add(text)
            }
        }
        if (newList.isNotEmpty()) {
            val importsText = newList.joinToString(separator = "\n") { it }
            classStringBuilder.append("$importsText\n\n")
        }
    }

    private fun addPackageName(classStringBuilder: StringBuilder, packageName: String) {
        classStringBuilder.append("package $packageName\n\n")
    }

    private fun PsiElement.reformat() {
        val ktFile = containingFile as KtFile
        removeUnusedImportDirectives(ktFile)
        CodeStyleManager.getInstance(project).reformat(this)
    }

    private fun removeUnusedImportDirectives(file: KtFile) {
        val importDirectives = file.importDirectives
        val unusedImportDirectives = importDirectives.filter { it.isUsedImportDirective(file).not() }
        unusedImportDirectives.forEach { it.delete() }
    }

    private fun KtImportDirective.isUsedImportDirective(file: KtFile): Boolean {
        if (importedFqName?.asString()?.endsWith("*") == true) return true

        val fileText = file.text
        val importShortName = importedFqName?.shortName()?.asString()
        val isUsedAsParameter = fileText.contains(": $importShortName,")
        val isUsed = fileText.contains("$importShortName.")
        return isUsedAsParameter || isUsed
    }
}

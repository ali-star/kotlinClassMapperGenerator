package com.alistar.kotlinclassmappergenerator

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.search.ProjectScope
import org.jetbrains.kotlin.asJava.classes.KtUltraLightClass
import org.jetbrains.kotlin.idea.caches.resolve.analyze
import org.jetbrains.kotlin.idea.completion.argList
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.isPrivate
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.types.isNullable
import org.jetbrains.kotlin.util.isAnnotated
import org.jetbrains.kotlinx.serialization.compiler.backend.common.serialName

class MapperGeneratorV2 {

    fun generateClass(
            ktClass: KtClass,
            parentKtClass: KtClass? = null,
            className: String,
            classSuffix: String,
            packageName: String,
            directory: PsiDirectory,
            project: Project,
    ) {
        val psiFactory = KtPsiFactory(project = project, markGenerated = true)
        val psiFileFactory = PsiFileFactory.getInstance(project)

        // If it has a parent class it means that the class is nested and the model for this
        // class needs to be generated inside the ktClass
        if (parentKtClass != null) {
            val dataClass = ktClass(
                psiFactory = psiFactory,
                className = className,
                classSuffix = classSuffix,
                ktClass = ktClass,
                project = project,
                packageName = packageName,
                directory = directory,
            )
            parentKtClass.addDeclaration(dataClass)
        } else {
            WriteCommandAction.runWriteCommandAction(project) {
                val modelFile = psiFileFactory.createFileFromText(
                        "$className$classSuffix.kt",
                        KotlinFileType(),
                        "package $packageName"
                )

                val primaryConstructorParameters = ktClass.primaryConstructorParameters

                val file = directory.add(modelFile) as KtFile
                addImports(ktClass.containingKtFile, file, primaryConstructorParameters)

                val dataClass = ktClass(
                    psiFactory = psiFactory,
                    className = className,
                    classSuffix = classSuffix,
                    ktClass = ktClass,
                    project = project,
                    packageName = packageName,
                    directory = directory,
                )

                file.add(dataClass)
            }
        }
    }

    private fun addImports(
        ktFile: KtFile,
        newFile: KtFile,
        primaryConstructorParameters: List<KtParameter>
    ) {
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
                newFile.importList?.add(import)
            }
        }
    }

    private fun ktClass(
        psiFactory: KtPsiFactory,
        className: String,
        classSuffix: String,
        ktClass: KtClass,
        project: Project,
        packageName: String,
        directory: PsiDirectory
    ): KtClass {
        val dataClass = psiFactory.createClass("data class $className$classSuffix")
        val constructor = dataClass.createPrimaryConstructorParameterListIfAbsent()

        val primaryConstructorParameters = ktClass.primaryConstructorParameters

        primaryConstructorParameters.forEach parametersLoop@{ parameter ->
            if (parameter.isPrivate()) {
                return@parametersLoop
            }

            // region possible improvement
            val typeReference = parameter.typeReference
            val bindingContext = typeReference?.analyze()
            val type = bindingContext?.get(BindingContext.TYPE, typeReference)
            val aSerialName = type?.serialName()

            val newPsiClass = JavaPsiFacade.getInstance(project)
                .findClass(aSerialName!!, ProjectScope.getAllScope(project))

            val ktUltraLightClass = newPsiClass as? KtUltraLightClass
            val nestedClass = ktUltraLightClass?.kotlinOrigin as? KtClass
            val nestedClassName = nestedClass?.fqName?.shortName()?.asString() ?: ""
            // endregion

            if (nestedClass != null) {
                if (nestedClass.containingKtFile == ktClass.containingKtFile) {
                    generateClass(
                        ktClass = nestedClass,
                        parentKtClass = dataClass,
                        className = nestedClassName,
                        classSuffix = classSuffix,
                        packageName = packageName,
                        directory = directory,
                        project = project,
                    )
                } else {
                    generateClass(
                        ktClass = nestedClass,
                        className = nestedClassName,
                        classSuffix = classSuffix,
                        packageName = packageName,
                        directory = directory,
                        project = project,
                    )
                }
            }

            // val parameterIsNestedClass = typeElement is KtClass
            val newParameter = if (nestedClass != null) {
                psiFactory.createParameter(
                    text = "val ${parameter.name}$classSuffix: $type$classSuffix"
                )
            } else {
                parameter
            }

            constructor.addParameter(newParameter)
        }
        return dataClass
    }

    fun generateMapper(
        ktClass: KtClass,
        rootFile: KtFile? = null,
        className: String,
        classSuffix: String,
        packageName: String,
        directory: PsiDirectory,
        project: Project,
    ) {
        val psiFactory = KtPsiFactory(project = project, markGenerated = true)
        val psiFileFactory = PsiFileFactory.getInstance(project)
        val elementFactory = PsiElementFactory.getInstance(project)

        WriteCommandAction.runWriteCommandAction(project) {
            val modelFile = psiFileFactory.createFileFromText(
                "$className${classSuffix}Mapper.kt",
                KotlinFileType(),
                "package $packageName"
            )

            val primaryConstructorParameters = ktClass.primaryConstructorParameters

            val file = rootFile ?: (directory.findFile(modelFile.name) ?: directory.add(modelFile)) as KtFile

            val ktClassName = ktClass.fqName?.asString()?.replace("$packageName.", "") ?: ""
            val mappedKtClassName = ktClassName.replace(".", "$classSuffix.") + classSuffix

            val function = psiFactory.createFunction("fun $ktClassName.mapTo$classSuffix():" +
                    " $mappedKtClassName = ")

            val arguments = HashMap<String, String>()

            primaryConstructorParameters.forEach parametersLoop@{ parameter ->
                if (parameter.isPrivate()) {
                    return@parametersLoop
                }

                // region possible improvement
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
                // endregion

                if (nestedClass != null) {
                    if (nestedClass.isData()) {
                        val parent = (nestedClass.parent as? KtClassBody)?.parent as? KtClass
                        generateMapper(
                            ktClass = nestedClass,
                            rootFile = if (parent != null) file else null,
                            className = nestedClassName,
                            classSuffix = classSuffix,
                            packageName = packageName,
                            directory = directory,
                            project = project,
                        )
                        arguments["${parameter.name}$classSuffix"] = name + (if (type.isNullable()) "?" else "") + ".mapTo$classSuffix()"
                    }
                } else {
                    arguments[parameter.name.toString()] = parameter.name.toString()
                }
            }

            val instantiationCode = elementFactory.createExpressionFromText(
                "$mappedKtClassName(${arguments.entries.joinToString { (paramName, paramValue) ->
                    "$paramName = $paramValue"
                }})",
                null
            )

            function.add(instantiationCode)
            file.add(function)

        }
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

package com.alistar.kotlinclassmappergenerator

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiManager
import org.jetbrains.kotlin.idea.base.psi.deleteSingle
import org.jetbrains.kotlin.idea.base.psi.kotlinFqName
import org.jetbrains.kotlin.idea.base.utils.fqname.fqName
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.isPrivate
import org.jetbrains.kotlin.resolve.ImportPath
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.TypeProjection
import org.jetbrains.kotlin.types.isNullable
import org.jetbrains.kotlin.types.typeUtil.*
import org.jetbrains.kotlin.util.isAnnotated

class EntityGeneratorV3 {

    fun generateClass(
        originalKtClass: KtClass,
        className: String,
        classSuffix: String,
        packageName: String,
        directory: PsiDirectory,
        project: Project,
        isRecursive: Boolean = true,
    ) {
        val psiFactory = KtPsiFactory(project = project, markGenerated = true)
        val psiFileFactory = PsiFileFactory.getInstance(project)
        val psiManager = PsiManager.getInstance(project)

        WriteCommandAction.runWriteCommandAction(project) {
            val filesMap = createFiles(
                project = project,
                ktClassToGenerateMapperFor = originalKtClass,
                className = className,
                classSuffix = classSuffix,
                packageName = packageName,
                directory = directory,
            )

            createClasses(
                project = project,
                ktClassToGenerateMapperFor = originalKtClass,
                className = className,
                classSuffix = classSuffix,
                packageName = packageName,
                directory = directory,
                filesMap = filesMap
            )

            filesMap.values.forEach { entry ->
                val file = psiManager.findFile(entry.virtualFile) as? KtFile ?: return@forEach
                val classes = file.declarations.filterIsInstance<KtClass>()
                if (classes.size < 2) return@forEach
                val reversedClasses = classes.map { it.copy() }.reversed()
                classes.forEach { it.deleteSingle() }
                reversedClasses.forEach { file.add(it) }
            }

            println("Created files: ${filesMap.entries.joinToString { (f1, f2) -> f1.name + " -> " + f2.name }}")
        }

        val documentManager: PsiDocumentManager = PsiDocumentManager.getInstance(project)
        documentManager.commitAllDocuments()
    }

    private fun createClasses(
        project: Project,
        ktClassToGenerateMapperFor: KtClass,
        className: String,
        classSuffix: String,
        packageName: String,
        directory: PsiDirectory,
        filesMap: Map<KtFile, KtFile>,
    ) {
        val psiFactory = KtPsiFactory(project = project, markGenerated = true)
        val psiFileFactory = PsiFileFactory.getInstance(project)

        if (ktClassToGenerateMapperFor.isData()) {
            createDataClass(
                project = project,
                ktClassToGenerateMapperFor = ktClassToGenerateMapperFor,
                className = className,
                classSuffix = classSuffix,
                packageName = packageName,
                directory = directory,
                filesMap = filesMap,
                psiFileFactory = psiFileFactory,
                psiFactory = psiFactory,
            )
        } else if (ktClassToGenerateMapperFor.isEnum()) {
            createEnumClass(
                project = project,
                ktClassToGenerateMapperFor = ktClassToGenerateMapperFor,
                className = className,
                classSuffix = classSuffix,
                packageName = packageName,
                directory = directory,
                filesMap = filesMap,
                psiFileFactory = psiFileFactory,
                psiFactory = psiFactory,
            )
        } else {
            throw Exception("Error")
        }
    }

    private fun createDataClass(
        ktClassToGenerateMapperFor: KtClass,
        className: String,
        project: Project,
        psiFactory: KtPsiFactory,
        filesMap: Map<KtFile, KtFile>,
        psiFileFactory: PsiFileFactory,
        classSuffix: String,
        packageName: String,
        directory: PsiDirectory,
        parentKtClassToContain: KtClass? = null
    ) {
        val imports = ArrayList<KtImportDirective>()
        val newClass = psiFactory.createClass("data class $className$classSuffix")
        val constructor = newClass.createPrimaryConstructorParameterListIfAbsent()

        val primaryConstructorParameters = ktClassToGenerateMapperFor.primaryConstructorParameters
        primaryConstructorParameters.forEach parametersLoop@{ parameter ->
            if (parameter.isPrivate()) return@parametersLoop

            val parameterInfo = parameter.getInfo()
            val ktClass = parameterInfo.ktClass
            val typeArguments = parameterInfo.type.arguments

            val newParameter = analyseParameter(
                ktClass = ktClass,
                project = project,
                parameterInfo = parameterInfo,
                classSuffix = classSuffix,
                packageName = packageName,
                directory = directory,
                filesMap = filesMap,
                psiFileFactory = psiFileFactory,
                psiFactory = psiFactory,
                ktClassToGenerateMapperFor = ktClassToGenerateMapperFor,
                newClass = newClass,
                typeArguments = typeArguments,
                className = className,
                imports = imports,
                parameter = parameter,
            )

            constructor.addParameter(newParameter)
        }

        if (ktClassToGenerateMapperFor.isNestedClass() && parentKtClassToContain != null) {
            parentKtClassToContain.addDeclaration(newClass)
        } else {
            val file = filesMap[ktClassToGenerateMapperFor.containingKtFile]

            if (file == null) {
                throw Exception("File doesn't exists: ${newClass.kotlinFqName}")
            } else {
                imports.also {
                    addOriginalImports(
                        ktFile = ktClassToGenerateMapperFor.containingKtFile,
                        newFile = file,
                        primaryConstructorParameters = ktClassToGenerateMapperFor.primaryConstructorParameters,
                    )
                }.forEach { import ->
                    // Check if the import is already exists
                    if (file.importList?.imports?.find { it.text.toString() == import.text.toString() } == null) {
                        file.importList?.add(import)
                    }
                }

                var isFileContainsClass = false
                file.classes.forEach { clazz ->
                    if (clazz.name == newClass.fqName?.asString()) {
                        isFileContainsClass = true
                        return@forEach
                    }
                }

                if (!isFileContainsClass) {
                    file.add(newClass)
                }
            }
        }
    }

    private fun analyzeGenericClass(
        typeArguments: List<TypeProjection>,
        ktClassToGenerateMapperFor: KtClass,
        className: String,
        project: Project,
        psiFactory: KtPsiFactory,
        filesMap: Map<KtFile, KtFile>,
        psiFileFactory: PsiFileFactory,
        classSuffix: String,
        packageName: String,
        directory: PsiDirectory,
        newClass: KtClass,
        parentKtClassToContain: KtClass? = null
    ): GenericClassInfo {
        val args = ArrayList<String>()
        val imports = ArrayList<KtImportDirective>()
        typeArguments.forEach typeArgumentsLoop@{ typeArgument ->
            val localTypeInfo = typeArgument.localTypeInfo(project = project)
            val localTypeInfoKtClass = localTypeInfo?.ktClass

            if (localTypeInfoKtClass == null) {
                val typeArgumentIsGeneric = typeArgument.type.arguments.isNotEmpty()
                if (typeArgumentIsGeneric) {
                    val genericClassInfo = analyzeGenericClass(
                        typeArguments = typeArgument.type.arguments,
                        ktClassToGenerateMapperFor = ktClassToGenerateMapperFor,
                        className = className,
                        project = project,
                        psiFactory = psiFactory,
                        filesMap = filesMap,
                        psiFileFactory = psiFileFactory,
                        classSuffix = classSuffix,
                        packageName = packageName,
                        directory = directory,
                        newClass = newClass,
                        parentKtClassToContain = parentKtClassToContain,
                    )

                    val typeText = typeArgument.type.fqName?.asString() ?: ""
                    val isNotBasicType = !typeArgument.type.isBasicType()
                    if (isNotBasicType) {
                        val import = psiFactory.createImportDirective(ImportPath.fromString(typeText))
                        imports.add(import)
                    }
                    args.add("$typeText<${genericClassInfo.args.joinToString { it }}>")
                    imports.addAll(genericClassInfo.imports)
                } else {
                    val typeText = typeArgument.type.fqName?.asString() ?: ""
                    val isNotBasicType = !typeArgument.type.isBasicType()
                    if (isNotBasicType) {
                        imports.add(psiFactory.createImportDirective(ImportPath.fromString(typeText)))
                    }
                    args.add(typeText.substringAfterLast("."))
                }
                return@typeArgumentsLoop
            } else if (localTypeInfoKtClass.isSupported()) {
                if (localTypeInfoKtClass.isData()) {
                    createDataClass(
                        project = project,
                        ktClassToGenerateMapperFor = localTypeInfoKtClass,
                        className = localTypeInfo.ktClassName,
                        classSuffix = classSuffix,
                        packageName = packageName,
                        directory = directory,
                        filesMap = filesMap,
                        psiFileFactory = psiFileFactory,
                        psiFactory = psiFactory,
                        parentKtClassToContain = if (localTypeInfoKtClass.isNestedClassOf(ktClassToGenerateMapperFor))
                            newClass else null
                    )

                    val typeText = localTypeInfo.ktClassName +
                            classSuffix +
                            if (typeArgument.type.isNullable()) "?" else ""

                    if (localTypeInfo.ktClass.isNestedClass()) {
                        imports.add(
                            psiFactory.createImportDirective(
                                ImportPath.fromString(localTypeInfo.ktClass.fqName?.asString() ?: "")
                            )
                        )
                    }
                    args.add(typeText)
                } else if (localTypeInfoKtClass.isEnum()) {
                    createEnumClass(
                        project = project,
                        ktClassToGenerateMapperFor = localTypeInfoKtClass,
                        className = localTypeInfo.ktClassName,
                        classSuffix = classSuffix,
                        packageName = packageName,
                        directory = directory,
                        filesMap = filesMap,
                        psiFileFactory = psiFileFactory,
                        psiFactory = psiFactory,
                    )

                    val typeText = localTypeInfo.ktClassName +
                            classSuffix +
                            if (typeArgument.type.isNullable()) "?" else ""

                    if (localTypeInfo.ktClass.isNestedClass()) {
                        imports.add(
                            psiFactory.createImportDirective(
                                ImportPath.fromString(localTypeInfo.ktClass.fqName?.asString() ?: "")
                            )
                        )
                    }
                    args.add(typeText)
                }
            } else {
                val arg = "/* Implement manually */"
                args.add(arg)
            }
        }
        return GenericClassInfo(
            args = args,
            imports = imports,
        )
    }

    private fun scanGenericClass(
        typeArguments: List<TypeProjection>,
        ktClassToGenerateMapperFor: KtClass,
        project: Project,
        psiFactory: KtPsiFactory,
        filesMap: HashMap<KtFile, KtFile>,
        psiFileFactory: PsiFileFactory,
        classSuffix: String,
        packageName: String,
        directory: PsiDirectory,
    ) {
        typeArguments.forEach typeArgumentsLoop@{ typeArgument ->
            val typeInfo = typeArgument.localTypeInfo(project = project)
            val typeInfoKtClass = typeInfo?.ktClass

            if (typeInfoKtClass == null) {
                val typeArgumentIsGeneric = typeArgument.type.arguments.isNotEmpty()
                if (typeArgumentIsGeneric) {
                    scanGenericClass(
                        typeArguments = typeArgument.type.arguments,
                        ktClassToGenerateMapperFor = ktClassToGenerateMapperFor,
                        project = project,
                        psiFactory = psiFactory,
                        psiFileFactory = psiFileFactory,
                        classSuffix = classSuffix,
                        packageName = packageName,
                        directory = directory,
                        filesMap = filesMap,
                    )
                }
            } else if (typeInfoKtClass.isSupported()) {
                if (typeInfoKtClass.isNotInsideSameClassFile(ktClassToGenerateMapperFor)
                    && typeInfoKtClass.isNotNestedClassOf(ktClass = ktClassToGenerateMapperFor)
                ) {
                    createFile(
                        psiFileFactory = psiFileFactory,
                        originalKtFile = typeInfoKtClass.containingKtFile,
                        className = typeInfo.ktClassName,
                        classSuffix = classSuffix,
                        packageName = packageName,
                        directory = directory,
                        filesMap = filesMap
                    )
                }
                scanClass(
                    ktClassToGenerateMapperFor = typeInfoKtClass,
                    psiFactory = psiFactory,
                    psiFileFactory = psiFileFactory,
                    project = project,
                    classSuffix = classSuffix,
                    packageName = packageName,
                    directory = directory,
                    filesMap = filesMap,
                )
            }
        }
    }

    private fun createEnumClass(
        ktClassToGenerateMapperFor: KtClass,
        className: String,
        project: Project,
        psiFactory: KtPsiFactory,
        filesMap: Map<KtFile, KtFile>,
        psiFileFactory: PsiFileFactory,
        classSuffix: String,
        packageName: String,
        directory: PsiDirectory,
    ) {
        val imports = ArrayList<KtImportDirective>()
        val text = buildString {
            append("enum class $className$classSuffix")
            append("{")
            if (ktClassToGenerateMapperFor.isEnum()) {
                append(ktClassToGenerateMapperFor.declarations.filterIsInstance<KtEnumEntry>().joinToString {
                    if (it.hasInitializer()) {
                        "${it.name}${it.initializerList?.text}"
                    } else {
                        it.name.toString()
                    }
                })
            }
            append("}")
        }
        val enumClass = psiFactory.createClass(text)
        if (ktClassToGenerateMapperFor.primaryConstructorParameters.isNotEmpty()) {
            val constructor = enumClass.createPrimaryConstructorParameterListIfAbsent()

            ktClassToGenerateMapperFor.primaryConstructorParameters.forEach parametersLoop@{ parameter ->
                if (parameter.isPrivate()) return@parametersLoop

                val parameterInfo = parameter.getInfo()
                val ktClass = parameterInfo.ktClass
                val typeArguments = parameterInfo.type.arguments

                val newParameter = analyseParameter(
                    ktClass = ktClass,
                    project = project,
                    parameterInfo = parameterInfo,
                    classSuffix = classSuffix,
                    packageName = packageName,
                    directory = directory,
                    filesMap = filesMap,
                    psiFileFactory = psiFileFactory,
                    psiFactory = psiFactory,
                    ktClassToGenerateMapperFor = ktClassToGenerateMapperFor,
                    newClass = enumClass,
                    typeArguments = typeArguments,
                    className = className,
                    imports = imports,
                    parameter = parameter,
                )

                constructor.addParameter(newParameter)
            }
        }

        val file = filesMap[ktClassToGenerateMapperFor.containingKtFile]!!

        imports.also {
            addOriginalImports(
                ktFile = ktClassToGenerateMapperFor.containingKtFile,
                newFile = file,
                primaryConstructorParameters = ktClassToGenerateMapperFor.primaryConstructorParameters,
            )
        }.forEach { import ->
            // Check if the import is already exists
            if (!file.hasImport(import)) {
                file.importList?.add(import)
            }
        }

        file.add(enumClass)
    }

    private fun analyseParameter(
        ktClass: KtClass?,
        project: Project,
        parameterInfo: ParameterInfo,
        classSuffix: String,
        packageName: String,
        directory: PsiDirectory,
        filesMap: Map<KtFile, KtFile>,
        psiFileFactory: PsiFileFactory,
        psiFactory: KtPsiFactory,
        ktClassToGenerateMapperFor: KtClass,
        newClass: KtClass,
        typeArguments: List<TypeProjection>,
        className: String,
        imports: ArrayList<KtImportDirective>,
        parameter: KtParameter
    ): KtParameter {
        val newParameter: KtParameter
        if (ktClass != null) {
            if (ktClass.isData()) {
                // we should check if the class is already exists to avoid duplication
                createDataClass(
                    project = project,
                    ktClassToGenerateMapperFor = ktClass,
                    className = parameterInfo.ktClassName,
                    classSuffix = classSuffix,
                    packageName = packageName,
                    directory = directory,
                    filesMap = filesMap,
                    psiFileFactory = psiFileFactory,
                    psiFactory = psiFactory,
                    parentKtClassToContain = if (ktClass.isNestedClassOf(ktClassToGenerateMapperFor))
                        newClass else null
                )
            } else if (ktClass.isEnum()) {
                createEnumClass(
                    project = project,
                    ktClassToGenerateMapperFor = ktClass,
                    className = parameterInfo.ktClassName,
                    classSuffix = classSuffix,
                    packageName = packageName,
                    directory = directory,
                    filesMap = filesMap,
                    psiFileFactory = psiFileFactory,
                    psiFactory = psiFactory,
                )
            }

            newParameter = psiFactory.createParameter(
                text = generateParameterText(
                    parameterInfo = parameterInfo,
                    classSuffix = classSuffix,
                )
            )
        } else if (typeArguments.isNotEmpty()) {
            val genericClassInfo = analyzeGenericClass(
                typeArguments = typeArguments,
                ktClassToGenerateMapperFor = ktClassToGenerateMapperFor,
                className = className,
                project = project,
                psiFactory = psiFactory,
                filesMap = filesMap,
                psiFileFactory = psiFileFactory,
                classSuffix = classSuffix,
                packageName = packageName,
                directory = directory,
                newClass = newClass,
                parentKtClassToContain = null,
            )
            genericClassInfo.imports.forEach {
                imports.add(it)
            }
            newParameter = psiFactory.createParameter(
                text = generateParameterTextForGenericClass(
                    parameterInfo = parameterInfo,
                    args = genericClassInfo.args,
                )
            )
        } else {
            newParameter = psiFactory.createParameter(
                text = "val ${parameter.name}: ${parameterInfo.type}"
            )
        }
        return newParameter
    }

    private fun KtFile.hasImport(import: KtImportDirective) = importList?.imports?.find {
        it.text.toString() == import.text.toString()
    } != null

    private fun addOriginalImports(
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
            if (!importIsAnnotation && !newFile.hasImport(import)) {
                newFile.importList?.add(import)
            }
        }
    }

    private fun KotlinType.isBasicType() = isInt() || isLong() || isShort() || isByte() || isFloat() || isDouble()
            || isChar() || isBoolean() || isAny() || fqName?.asString() == "kotlin.String"


    private fun generateParameterText(
        parameterInfo: ParameterInfo,
        classSuffix: String,
    ): String {
        val newParameterNameText = parameterInfo.name + classSuffix
        val newParameterTypeText = parameterInfo.type.fqName?.shortName()?.asString()
            ?.replace("?", "") + classSuffix + if (parameterInfo.type.isNullable()) "?" else ""
        val newParameterText = "val $newParameterNameText: $newParameterTypeText"
        return newParameterText
    }

    private fun generateParameterTextForGenericClass(
        parameterInfo: ParameterInfo,
        args: List<String>
    ): String {
        val newParameterText = buildString {
            append("val ")
            append(parameterInfo.name)
            append(":")
            append(parameterInfo.type.fqName?.asString()?.substringAfterLast("."))
            append("<")
            append(args.joinToString { it })
            append(">")
            if (parameterInfo.type.isNullable()) {
                append("?")
            }
        }
        return newParameterText
    }

    private fun createFiles(
        project: Project,
        ktClassToGenerateMapperFor: KtClass,
        className: String,
        classSuffix: String,
        packageName: String,
        directory: PsiDirectory,
    ): Map<KtFile, KtFile> {
        val psiFactory = KtPsiFactory(project = project, markGenerated = true)
        val psiFileFactory = PsiFileFactory.getInstance(project)

        val filesMap = HashMap<KtFile, KtFile>()

        createFile(
            psiFileFactory = psiFileFactory,
            originalKtFile = ktClassToGenerateMapperFor.containingKtFile,
            className = className,
            classSuffix = classSuffix,
            packageName = packageName,
            directory = directory,
            filesMap = filesMap,
        )

        scanClass(
            ktClassToGenerateMapperFor = ktClassToGenerateMapperFor,
            psiFactory = psiFactory,
            psiFileFactory = psiFileFactory,
            project = project,
            classSuffix = classSuffix,
            packageName = packageName,
            directory = directory,
            filesMap = filesMap,
        )

        return filesMap
    }

    private fun scanClass(
        ktClassToGenerateMapperFor: KtClass,
        project: Project,
        psiFactory: KtPsiFactory,
        filesMap: HashMap<KtFile, KtFile>,
        psiFileFactory: PsiFileFactory,
        classSuffix: String,
        packageName: String,
        directory: PsiDirectory,
    ) {
        val primaryConstructorParameters = ktClassToGenerateMapperFor.primaryConstructorParameters
        primaryConstructorParameters.forEach parametersLoop@{ parameter ->
            if (parameter.isPrivate()) return@parametersLoop

            val parameterInfo = parameter.getInfo()
            val ktClass = parameterInfo.ktClass
            val typeArguments = parameterInfo.type.arguments

            if (ktClass != null) {
                if (ktClass.isNotSupported()) return@parametersLoop

                if (ktClass.isNotInsideSameClassFile(ktClassToGenerateMapperFor)
                    && ktClass.isNotNestedClassOf(ktClass = ktClassToGenerateMapperFor)
                ) {
                    createFile(
                        psiFileFactory = psiFileFactory,
                        originalKtFile = ktClass.containingKtFile,
                        className = parameterInfo.ktClassName,
                        classSuffix = classSuffix,
                        packageName = packageName,
                        directory = directory,
                        filesMap = filesMap
                    )
                }

                scanClass(
                    ktClassToGenerateMapperFor = ktClass,
                    psiFactory = psiFactory,
                    psiFileFactory = psiFileFactory,
                    project = project,
                    classSuffix = classSuffix,
                    packageName = packageName,
                    directory = directory,
                    filesMap = filesMap,
                )
            } else if (typeArguments.isNotEmpty()) {
                scanGenericClass(
                    typeArguments = typeArguments,
                    ktClassToGenerateMapperFor = ktClassToGenerateMapperFor,
                    project = project,
                    psiFactory = psiFactory,
                    filesMap = filesMap,
                    psiFileFactory = psiFileFactory,
                    classSuffix = classSuffix,
                    packageName = packageName,
                    directory = directory,
                )
            }
        }
    }

    private fun KtClass.isInsideSameClassFile(ktClas: KtClass) = containingKtFile == ktClas.containingKtFile

    private fun KtClass.isNotInsideSameClassFile(ktClas: KtClass) = !isInsideSameClassFile(ktClas)

    private fun createFile(
        psiFileFactory: PsiFileFactory,
        originalKtFile: KtFile,
        className: String,
        classSuffix: String,
        packageName: String,
        directory: PsiDirectory,
        filesMap: HashMap<KtFile, KtFile>
    ) {
        val fileName = "$className$classSuffix.kt"
        if (directory.findFile(fileName) != null) return

        val modelFile = psiFileFactory.createFileFromText(
            fileName,
            KotlinFileType(),
            "package $packageName"
        )

        val newFile = directory.add(modelFile) as KtFile
        filesMap[originalKtFile] = newFile
    }

    private fun KtClass.isSupported() = isData() || isEnum()

    private fun KtClass.isNotSupported() = !isSupported()

    private fun KtClass.isNestedClassOf(ktClassToGenerateMapperFor: KtClass) =
        parent.kotlinFqName == ktClassToGenerateMapperFor.fqName

    private fun KtClass.isNotNestedClassOf(ktClass: KtClass) = !isNestedClassOf(ktClass)

    private fun KtClass.isNestedClass() = parent.kotlinFqName != null

    private fun KtClass.isNotNestedClass() = !isNestedClass()
}

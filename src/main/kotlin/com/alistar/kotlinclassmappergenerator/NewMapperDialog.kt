package com.alistar.kotlinclassmappergenerator

import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.Container
import java.awt.GridLayout
import java.awt.Toolkit
import java.awt.event.ActionListener
import java.awt.event.KeyEvent
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.KeyStroke
import javax.swing.Spring
import javax.swing.SpringLayout
import javax.swing.SwingUtilities

class NewMapperDialog(
    private val className: String,
) {

    fun show(callback: (className: String, classSuffix: String) -> (Unit)) {
        val dialog = JDialog()

        val escapeListener = ActionListener { dialog.isVisible = false }
        dialog.rootPane.registerKeyboardAction(
            escapeListener,
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            JComponent.WHEN_IN_FOCUSED_WINDOW
        )

        val screenSize = Toolkit.getDefaultToolkit().screenSize

        val contentPane = dialog.contentPane
        val layout = BoxLayout(contentPane, BoxLayout.Y_AXIS)
        contentPane.layout = layout

        val classNameLabel = JLabel("Class Name: ")
        val classNameTextField = JTextField(className)

        val classSuffixLabel = JLabel("Class suffix: ")
        val classSuffixTextField = JTextField("Model")

        val labels = arrayOf("Class Name: ", "Class suffix: ")
        val numPairs = labels.size

        val parent = JPanel(SpringLayout())
        parent.add(classNameLabel)
        parent.add(classNameTextField)

        parent.add(classSuffixLabel)
        parent.add(classSuffixTextField)

        makeCompactGrid(
            parent = parent,
            rows = numPairs,
            cols = 2,
            initialX = 6,
            initialY = 6,
            xPad = 6,
            yPad = 6,
        )

        dialog.add(parent)

        val jPanel = JPanel(GridLayout(1, 1))
        jPanel.border = JBUI.Borders.empty(0, 5, 5, 5)
        val button = JButton("Generate")
        button.addActionListener {
            callback(classNameTextField.text, classSuffixTextField.text)
            dialog.isVisible = false
        }
        SwingUtilities.getRootPane(dialog).defaultButton = button
        jPanel.add(button)
        dialog.add(jPanel)

        dialog.pack()
        dialog.setSize(300, dialog.height)
        dialog.setLocation((screenSize.width - dialog.width) / 2, (screenSize.height - dialog.height) / 2)
        dialog.isResizable = false
        dialog.isVisible = true
    }

    private fun makeCompactGrid(
        parent: Container,
        rows: Int, cols: Int,
        initialX: Int, initialY: Int,
        xPad: Int, yPad: Int
    ) {
        val layout: SpringLayout = try {
            parent.layout as SpringLayout
        } catch (exc: ClassCastException) {
            System.err.println("The first argument to makeCompactGrid must use SpringLayout.")
            return
        }

        //Align all cells in each column and make them the same width.
        var x = Spring.constant(initialX)
        for (c in 0 until cols) {
            var width = Spring.constant(0)
            for (r in 0 until rows) {
                width = Spring.max(
                    width,
                    getConstraintsForCell(r, c, parent, cols).width
                )
            }
            for (r in 0 until rows) {
                val constraints: SpringLayout.Constraints = getConstraintsForCell(r, c, parent, cols)
                constraints.x = x
                constraints.width = width
            }
            x = Spring.sum(x, Spring.sum(width, Spring.constant(xPad)))
        }

        //Align all cells in each row and make them the same height.
        var y = Spring.constant(initialY)
        for (r in 0 until rows) {
            var height = Spring.constant(0)
            for (c in 0 until cols) {
                height = Spring.max(
                    height,
                    getConstraintsForCell(r, c, parent, cols).height
                )
            }
            for (c in 0 until cols) {
                val constraints: SpringLayout.Constraints = getConstraintsForCell(r, c, parent, cols)
                constraints.y = y
                constraints.height = height
            }
            y = Spring.sum(y, Spring.sum(height, Spring.constant(yPad)))
        }

        //Set the parent's size.
        val pCons = layout.getConstraints(parent)
        pCons.setConstraint(SpringLayout.SOUTH, y)
        pCons.setConstraint(SpringLayout.EAST, x)
    }

    private fun getConstraintsForCell(
        row: Int, col: Int,
        parent: Container,
        cols: Int
    ): SpringLayout.Constraints {
        val layout = parent.layout as SpringLayout
        val c: Component = parent.getComponent(row * cols + col)
        return layout.getConstraints(c)
    }
}
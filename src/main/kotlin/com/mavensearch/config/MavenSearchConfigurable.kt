package com.mavensearch.config

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Maven For Search 设置面板 (Settings → Tools → Maven For Search)
 */
class MavenSearchConfigurable : Configurable {

    private val panel = JPanel(GridBagLayout())
    private val searchSourceCombo = ComboBox(DefaultComboBoxModel(SearchSource.entries.toTypedArray()))
    private val debounceField = JBTextField()
    private val pageSizeField = JBTextField()
    private val timeoutField = JBTextField()
    private val downloadJarCheck = JBCheckBox("Enable download jar to project")
    private val addToPomCheck = JBCheckBox("Enable add dependency to pom.xml")
    private val jarDirField = JBTextField()

    private val settings get() = MavenSearchSettings.getInstance()

    override fun getDisplayName(): String = "Maven For Search"

    override fun createComponent(): JComponent {
        val gbc = GridBagConstraints().apply {
            insets = Insets(4, 4, 4, 4)
            anchor = GridBagConstraints.WEST
        }

        var row = 0

        gbc.gridy = row++; gbc.gridx = 0; panel.add(JBLabel("Search source:"), gbc)
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
        panel.add(searchSourceCombo, gbc)

        gbc.gridy = row++; gbc.gridx = 0; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0
        panel.add(JBLabel("Debounce delay (ms):"), gbc)
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
        panel.add(debounceField, gbc)

        gbc.gridy = row++; gbc.gridx = 0; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0
        panel.add(JBLabel("Page size:"), gbc)
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
        panel.add(pageSizeField, gbc)

        gbc.gridy = row++; gbc.gridx = 0; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0
        panel.add(JBLabel("Network timeout (sec):"), gbc)
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
        panel.add(timeoutField, gbc)

        gbc.gridy = row++; gbc.gridx = 0; gbc.gridwidth = 2; gbc.fill = GridBagConstraints.NONE
        panel.add(downloadJarCheck, gbc)

        gbc.gridy = row++; gbc.gridx = 0; panel.add(JBLabel("Jar download dir:"), gbc)
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
        panel.add(jarDirField, gbc)

        gbc.gridy = row++; gbc.gridx = 0; gbc.gridwidth = 2; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0
        panel.add(addToPomCheck, gbc)

        // 填充空白
        gbc.gridy = row++; gbc.gridx = 0; gbc.gridwidth = 2
        gbc.fill = GridBagConstraints.BOTH; gbc.weighty = 1.0
        panel.add(JPanel(), gbc)

        return panel
    }

    override fun isModified(): Boolean {
        val s = settings.state
        return searchSourceCombo.selectedItem != settings.searchSource ||
                debounceField.text != s.debounceDelayMs.toString() ||
                pageSizeField.text != s.pageSize.toString() ||
                timeoutField.text != s.networkTimeoutSec.toString() ||
                downloadJarCheck.isSelected != s.enableDownloadJar ||
                addToPomCheck.isSelected != s.enableAddToPom ||
                jarDirField.text != s.jarDownloadDir
    }

    override fun apply() {
        val s = settings.state
        s.searchSource = (searchSourceCombo.selectedItem as SearchSource).name
        s.debounceDelayMs = debounceField.text.toIntOrNull() ?: 300
        s.pageSize = pageSizeField.text.toIntOrNull() ?: 20
        s.networkTimeoutSec = timeoutField.text.toIntOrNull() ?: 10
        s.enableDownloadJar = downloadJarCheck.isSelected
        s.enableAddToPom = addToPomCheck.isSelected
        s.jarDownloadDir = jarDirField.text.ifBlank { "lib" }
    }

    override fun reset() {
        val s = settings.state
        searchSourceCombo.selectedItem = settings.searchSource
        debounceField.text = s.debounceDelayMs.toString()
        pageSizeField.text = s.pageSize.toString()
        timeoutField.text = s.networkTimeoutSec.toString()
        downloadJarCheck.isSelected = s.enableDownloadJar
        addToPomCheck.isSelected = s.enableAddToPom
        jarDirField.text = s.jarDownloadDir
    }
}

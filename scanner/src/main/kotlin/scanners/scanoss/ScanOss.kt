/*
 * Copyright (C) 2020-2021 SCANOSS TECNOLOGIAS SL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 * License-Filename: LICENSE
 */

package org.ossreviewtoolkit.scanner.scanners.scanoss

import com.fasterxml.jackson.databind.JsonNode

import com.scanoss.scanner.ScanFormat
import com.scanoss.scanner.Scanner
import com.scanoss.scanner.ScannerConf

import java.io.File
import java.time.Instant

import org.ossreviewtoolkit.model.ScanSummary
import org.ossreviewtoolkit.model.config.DownloaderConfiguration
import org.ossreviewtoolkit.model.config.ScannerConfiguration
import org.ossreviewtoolkit.model.readJsonFile
import org.ossreviewtoolkit.scanner.AbstractScannerFactory
import org.ossreviewtoolkit.scanner.RemoteScanner

class ScanOss(
    name: String,
    scannerConfig: ScannerConfiguration,
    downloaderConfig: DownloaderConfiguration
) : RemoteScanner(name, scannerConfig, downloaderConfig) {
    class Factory : AbstractScannerFactory<ScanOss>("SCANOSS") {
        override fun create(scannerConfig: ScannerConfiguration, downloaderConfig: DownloaderConfiguration) =
            ScanOss(scannerName, scannerConfig, downloaderConfig)
    }

    override val version = "1.1.1"

    override val configuration = ""

    override val resultFileExt = "json"

    override fun scanPathInternal(path: File, resultsFile: File): ScanSummary {
        val startTime = Instant.now()

        // TODO: Support API configurations other than OSSKB.
        val scannerConf = ScannerConf.defaultConf()

        val scanner = Scanner(scannerConf)

        // TODO: Implement support for scanning with SBOM.
        if (path.isDirectory) {
            scanner.scanDirectory(path.absolutePath, null, "", ScanFormat.plain, resultsFile.absolutePath)
        } else if (path.isFile) {
            scanner.scanFileAndSave(path.absolutePath, null, "", ScanFormat.plain, resultsFile.absolutePath)
        }

        val endTime = Instant.now()

        val result = readJsonFile(resultsFile)
        return generateSummary(startTime, endTime, path, result)
    }
}

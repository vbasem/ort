/*
 * Copyright (C) 2017-2019 HERE Europe B.V.
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

package org.ossreviewtoolkit.scanner.scanners

import com.fasterxml.jackson.databind.JsonNode

import org.ossreviewtoolkit.model.EMPTY_JSON_NODE
import org.ossreviewtoolkit.model.LicenseFinding
import org.ossreviewtoolkit.model.Provenance
import org.ossreviewtoolkit.model.ScanResult
import org.ossreviewtoolkit.model.ScanSummary
import org.ossreviewtoolkit.model.TextLocation
import org.ossreviewtoolkit.model.config.ScannerConfiguration
import org.ossreviewtoolkit.model.yamlMapper
import org.ossreviewtoolkit.scanner.AbstractScannerFactory
import org.ossreviewtoolkit.scanner.LocalScanner
import org.ossreviewtoolkit.scanner.ScanException
import org.ossreviewtoolkit.spdx.calculatePackageVerificationCode
import org.ossreviewtoolkit.utils.ORT_NAME
import org.ossreviewtoolkit.utils.Os
import org.ossreviewtoolkit.utils.OkHttpClientHelper
import org.ossreviewtoolkit.utils.ProcessCapture
import org.ossreviewtoolkit.utils.log

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.time.Instant

import okhttp3.Request

import okio.buffer
import okio.sink

class Askalono(name: String, config: ScannerConfiguration) : LocalScanner(name, config) {
    class Factory : AbstractScannerFactory<Askalono>("Askalono") {
        override fun create(config: ScannerConfiguration) = Askalono(scannerName, config)
    }

    override val scannerVersion = "0.4.2"
    override val resultFileExt = "txt"

    override fun command(workingDir: File?): String {
        val extension = when {
            Os.isLinux -> "linux"
            Os.isMac -> "osx"
            Os.isWindows -> "exe"
            else -> throw IllegalArgumentException("Unsupported operating system.")
        }

        return listOfNotNull(workingDir, "askalono.$extension").joinToString(File.separator)
    }

    override fun transformVersion(output: String) =
        // "askalono --version" returns a string like "askalono 0.2.0-beta.1", so simply remove the prefix.
        output.removePrefix("askalono ")

    override fun bootstrap(): File {
        val scannerExe = command()
        val url = "https://github.com/amzn/askalono/releases/download/$scannerVersion/$scannerExe"

        log.info { "Downloading $scannerName from $url... " }

        val request = Request.Builder().get().url(url).build()

        return OkHttpClientHelper.execute(request).use { response ->
            val body = response.body

            if (response.code != HttpURLConnection.HTTP_OK || body == null) {
                throw IOException("Failed to download $scannerName from $url.")
            }

            if (response.cacheResponse != null) {
                log.info { "Retrieved $scannerName from local cache." }
            }

            val scannerDir = createTempDir(ORT_NAME, "$scannerName-$scannerVersion").apply { deleteOnExit() }

            val scannerFile = File(scannerDir, scannerExe)
            scannerFile.sink().buffer().use { it.writeAll(body.source()) }

            if (!Os.isWindows) {
                // Ensure the executable Unix mode bit to be set.
                scannerFile.setExecutable(true)
            }

            scannerDir
        }
    }

    override fun getConfiguration() = ""

    override fun scanPathInternal(path: File, resultsFile: File): ScanResult {
        val startTime = Instant.now()

        val process = ProcessCapture(
            scannerPath.absolutePath,
            "crawl", path.absolutePath
        )

        val endTime = Instant.now()

        if (process.stderr.isNotBlank()) {
            log.debug { process.stderr }
        }

        with(process) {
            if (isSuccess) {
                stdoutFile.copyTo(resultsFile)
                val result = getRawResult(resultsFile)
                val summary = generateSummary(startTime, endTime, path, result)
                return ScanResult(Provenance(), getDetails(), summary, result)
            } else {
                throw ScanException(errorMessage)
            }
        }
    }

    override fun getRawResult(resultsFile: File): JsonNode {
        if (!resultsFile.isFile || resultsFile.length() == 0L) return EMPTY_JSON_NODE

        val yamlNodes = resultsFile.readLines().chunked(3) { (path, license, score) ->
            val licenseNoOriginalText = license.substringBeforeLast(" (original text)")
            val yamlString = listOf("Path: $path", licenseNoOriginalText, score).joinToString("\n")
            yamlMapper.readTree(yamlString)
        }

        return yamlMapper.createArrayNode().apply { addAll(yamlNodes) }
    }

    private fun generateSummary(startTime: Instant, endTime: Instant, scanPath: File, result: JsonNode): ScanSummary {
        val licenseFindings = sortedSetOf<LicenseFinding>()

        result.mapTo(licenseFindings) {
            val filePath = File(it["Path"].textValue())
            LicenseFinding(
                license = getSpdxLicenseIdString(it["License"].textValue()),
                location = TextLocation(
                    // Turn absolute paths in the native result into relative paths to not expose any information.
                    relativizePath(scanPath, filePath),
                    TextLocation.UNKNOWN_LINE,
                    TextLocation.UNKNOWN_LINE
                )
            )
        }

        return ScanSummary(
            startTime = startTime,
            endTime = endTime,
            fileCount = result.size(),
            packageVerificationCode = calculatePackageVerificationCode(scanPath),
            licenseFindings = licenseFindings,
            copyrightFindings = sortedSetOf(),
            issues = mutableListOf()
        )
    }
}

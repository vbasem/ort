/*
 * Copyright (C) 2020 HERE Europe B.V.
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

package org.ossreviewtoolkit

import java.io.File
import java.lang.StringBuilder
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

import org.ossreviewtoolkit.model.Provenance
import org.ossreviewtoolkit.model.config.FileStorageConfiguration
import org.ossreviewtoolkit.model.config.HttpFileStorageConfiguration
import org.ossreviewtoolkit.model.config.PostgresStorageConfiguration
import org.ossreviewtoolkit.scanner.ScanResultsStorage
import org.ossreviewtoolkit.scanner.storages.PostgresStorage
import org.ossreviewtoolkit.spdx.toHexString
import org.ossreviewtoolkit.utils.safeDeleteRecursively
import org.ossreviewtoolkit.utils.storage.FileArchiver
import org.ossreviewtoolkit.utils.storage.FileStorage

/**
 * BEGIN CONFIG
 */
// http file storage for the file archiver:
private const val JFROG_API_KEY = ""
private const val OLD_ARCHIVES_URL = ""
private const val NEW_ARCHIVES_URL = ""

// scan result storage
private const val PG_HOST = ""
private const val PG_PORT = ""
private const val PG_SCHEMA = ""
private const val PG_DATABASE = ""
private const val PG_USER = ""
private const val PG_PASSWORD = ""
/**
 * END CONFIG
 */

private fun createPostgresStorage(): PostgresStorage {
    val config = PostgresStorageConfiguration(
        url = "jdbc:postgresql://$PG_HOST:$PG_PORT/$PG_DATABASE",
        schema=PG_SCHEMA,
        username=PG_USER,
        password=PG_PASSWORD,
        sslmode = "disable"
    )

    return ScanResultsStorage.createPostgresStorage(config)
}

private fun createOldFileStorage(): FileStorage {
    val config = FileStorageConfiguration(
        httpFileStorage = HttpFileStorageConfiguration(
            url = OLD_ARCHIVES_URL,
            headers = mapOf("X-JFrog-Art-Api" to JFROG_API_KEY)
        )
    )

    return config.createFileStorage()
}

private fun createNewFileStorage(): FileStorage {
    val config = FileStorageConfiguration(
        httpFileStorage = HttpFileStorageConfiguration(
            url = NEW_ARCHIVES_URL,
            headers = mapOf("X-JFrog-Art-Api" to JFROG_API_KEY)
        )
    )

    return config.createFileStorage()
}

private val SHA1_DIGEST by lazy { MessageDigest.getInstance("SHA-1") }

/**
 * Calculate the SHA-1 hash of the storage key of this [Provenance] instance.
 */
private fun Provenance.newHash(): String {
    val key = vcsInfo?.let {
        "${it.type}${it.url}${it.resolvedRevision}"
    } ?: sourceArtifact!!.let {
        "${it.url}${it.hash.value}"
    }

    return SHA1_DIGEST.digest(key.toByteArray()).toHexString()
}

fun main() {
    val scanResultStorage = createPostgresStorage()
    val oldArchiver = FileArchiver(patterns = emptyList(), storage = createOldFileStorage())
    val newFileArchiver = FileArchiver(patterns = listOf("**/*", "*"), storage = createNewFileStorage())

    data class Entry(
        val newStoragePath: String,
        val oldStoragePaths: Set<String>
    )

    println("Fetching all id / provenance pairs from the scan result storage.")
    val queue = scanResultStorage.getEntries().groupBy(
        { (_ , provenance) -> provenance.newHash() },
        { (id, provenance) -> "${id.toPath()}/${provenance.hash()}" }
    ).mapTo(ConcurrentLinkedQueue()) { Entry(it.key, it.value.toSet()) }

    val numThreads = 64
    val executor = Executors.newFixedThreadPool(numThreads)
    val doneEntries = AtomicInteger()
    val totalEntries = queue.size
    val failureCount = AtomicInteger()

    for (i in 1 .. 64) {
        fun migrate(entry: Entry) {
            val sb = StringBuilder()

            val tempDir = Files.createTempDirectory("zip-archives-migration").toFile()
            val entryIndex = doneEntries.addAndGet(1)
            sb.appendLine("[$entryIndex/$totalEntries] Creating entries for '${entry.newStoragePath}' from ${entry.oldStoragePaths.size} entries.")

            entry.oldStoragePaths.forEach { oldStoragePath ->
                val countBefore = tempDir.count()

                if (!oldArchiver.unarchive(tempDir, oldStoragePath)) {
                    sb.appendLine("Failed unarchiving $oldStoragePath")
                    failureCount.getAndIncrement()
                } else {
                    sb.appendLine("Unarchived '$oldStoragePath' to '${tempDir.absolutePath}'. FileCount: ${tempDir.count()}.")
                }

                if (countBefore != 0 && tempDir.count() > countBefore) {
                    sb.appendLine("MERGED ARCHIVES!!!")
                }
            }

            newFileArchiver.archive(tempDir, entry.newStoragePath)
            sb.appendLine("Archived '$tempDir' to '${entry.newStoragePath}'. FileCount: ${tempDir.count()}.")

            tempDir.safeDeleteRecursively()

            println("[thread-$i]:\n${sb}" )
        }

        executor.execute {
            var entry = queue.poll()
            while (entry != null) {
                migrate(entry)
                entry = queue.poll()
            }
        }
    }
    println("Awaiting termination...")
    executor.shutdown()
    executor.awaitTermination(21, TimeUnit.DAYS)
    println("#total-failures: $failureCount")
}

private fun File.count(): Int = listFiles().size + listFiles().filter { it.isDirectory }.sumBy { it.count() }

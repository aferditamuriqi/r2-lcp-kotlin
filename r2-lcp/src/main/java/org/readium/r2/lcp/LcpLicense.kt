/*
 * Module: r2-lcp-kotlin
 * Developers: Aferdita Muriqi, Clément Baumann
 *
 * Copyright (c) 2018. Readium Foundation. All rights reserved.
 * Use of this source code is governed by a BSD-style license which is detailed in the
 * LICENSE file present in the project repository where this source code is maintained.
 */

package org.readium.r2.lcp

import android.bluetooth.BluetoothAdapter
import android.content.Context
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.joda.time.DateTime
import org.readium.lcp.sdk.DRMContext
import org.readium.lcp.sdk.Lcp
import org.readium.r2.lcp.model.documents.LicenseDocument
import org.readium.r2.lcp.model.documents.StatusDocument
import org.readium.r2.shared.drm.DrmLicense
import org.zeroturnaround.zip.ZipUtil
import timber.log.Timber
import java.io.File
import java.net.URL
import java.util.*
import java.util.zip.ZipFile

const val lcplFilePath = "META-INF/license.lcpl"

class LcpLicense : DrmLicense {

    var archivePath: URL? = null
    var license: LicenseDocument
    var status: StatusDocument? = null
    var context: DRMContext? = null
    var androidContext:Context
    val lcpHttpService: LcpHttpService = LcpHttpService()
    val database:LcpDatabase

    constructor(lcplData: ByteArray, context: Context) {

        androidContext = context

        database = LcpDatabase(androidContext)

        val data: ByteArray  = lcplData
        license = LicenseDocument(data)

    }

    constructor(path: URL, inArchive: Boolean, context: Context) {

        androidContext = context
        archivePath = path

        database = LcpDatabase(androidContext)

        val data: ByteArray
        if (!inArchive) {
            data = path.openStream().readBytes()
            license = LicenseDocument(data)
        } else {
            val url  = lcplFilePath
            data = getData(url, path)
            license = LicenseDocument(data)
        }

    }

    fun evaluate(bytes: ByteArray):String? = runBlocking {
        launch {
            Timber.i("evaluate: 1. fetchStatusDocument")
            fetchStatusDocument()
        }.join()
        launch {
            Timber.i("evaluate: 2. checkStatus")
            checkStatus()
        }.join()
        launch {
            Timber.i("evaluate: 3. updateLicenseDocument")
            updateLicenseDocument()
        }.join()
        launch {
            Timber.i("evaluate: 4. areRightsValid")
            areRightsValid()
        }.join()
        launch {
            Timber.i("evaluate: 5. register")
            register()
        }.join()
        var path:String? = null
        launch {
            Timber.i("evaluate: 6. fetchPublication")
            fetchPublication()?.let {
                Timber.i("evaluate: 7. moveLicense")
                moveLicense(it, bytes)
                path = it
            }
        }.join()
        Timber.i("evaluate: 8. return path $path")
        return@runBlocking path
    }.toString()

    fun resolve() = runBlocking {
        launch {
            Timber.i("resolve: 1. fetchStatusDocument")
            fetchStatusDocument()
        }.join()
        launch {
            Timber.i("resolve: 2. checkStatus")
            checkStatus()
        }.join()
        launch {
            Timber.i("resolve: 3. updateLicenseDocument")
            updateLicenseDocument()
        }.join()
    }

    override fun decipher(data: ByteArray) : ByteArray? {
        if (context == null)
            throw Exception(LcpError().errorDescription(LcpErrorCase.invalidContext))
        return Lcp().decrypt(context!!, data)
    }

    fun fetchStatusDocument() {
        Timber.i("LCP fetchStatusDocument")
        val statusLink = license.link("status")
        statusLink?.let {
            val document = lcpHttpService.statusDocument(it.href.toString())
            status = document as StatusDocument
        }
    }

    // If start is null or after now, or if END is null or before now, throw invalidRights Exception
    override fun areRightsValid() {
        Timber.i("LCP areRightsValid")
        val now = Date()
        license.rights?.start.let {
            if (it != null && it.toDate().after(now)) {
                throw Exception(LcpError().errorDescription(LcpErrorCase.invalidRights))
            }
        }
        license.rights?.end.let {
            if (it != null && it.toDate().before(now)) {
                throw Exception(LcpError().errorDescription(LcpErrorCase.invalidRights))
            }
        }
    }

    fun checkStatus() {
        Timber.i("LCP checkStatus")
        when (if (status?.status != null) status?.status else throw Exception(LcpError().errorDescription(LcpErrorCase.missingLicenseStatus))){
            StatusDocument.Status.returned -> throw Exception(LcpError().errorDescription(LcpErrorCase.licenseStatusReturned))
            StatusDocument.Status.expired -> throw Exception(LcpError().errorDescription(LcpErrorCase.licenseStatusExpired))
            StatusDocument.Status.revoked -> throw Exception(LcpError().errorDescription(LcpErrorCase.licenseStatusRevoked))
            StatusDocument.Status.cancelled -> throw Exception(LcpError().errorDescription(LcpErrorCase.licenseStatusCancelled))
            else -> {}
        }
    }

    override fun register() {
        Timber.i("LCP register")

        val date = database.licenses.dateOfLastUpdate(license.id)
        Timber.i( "LCP dateOfLastUpdate $date")

        if (!database.licenses.existingLicense(license.id)) return
        if (status == null) return

        val url = status?.link("register")?.href ?: return
        val registerUrl = URL(url.toString().replace("{?id,name}", ""))

        val params = listOf(
                "id" to getDeviceId(),
                "name" to getDeviceName())
        try {
            lcpHttpService.register(registerUrl.toString(), params)?.let {
                database.licenses.updateLicense(license, it)
            }
        }catch (e:Exception) {
            Timber.e( "LCP register ${e.message}")
        }

    }

    override fun returnLicense( callback: (Any) -> Unit) {
        Timber.i("LCP return")

        if (status == null) return

        val url = status?.link("return")?.href ?: return

        val returnUrl = URL(url.toString().replace("{?id,name}", ""))
        val params = listOf(
                "id" to getDeviceId(),
                "name" to getDeviceName())

        try {
            lcpHttpService.returnLicense(returnUrl.toString(), params)?.let {
                database.licenses.updateState(license.id, it)
            }
        } catch (e: Exception) {
            Timber.e( "LCP return ${e.message}")
        }

        callback(license)
    }

    override fun renewLicense(endDate: DateTime?, callback: (Any) -> Unit) {
        Timber.i("LCP renew")

        if (status == null) return
        val url = status?.link("renew")?.href ?: return

        val renewUrl = URL(url.toString().replace("{?end,id,name}", ""))
        val params = listOf(
                "end" to endDate,
                "id" to getDeviceId(),
                "name" to getDeviceName())

        try {
            lcpHttpService.renewLicense(renewUrl.toString(),params)?.let {
                database.licenses.updateState(license.id, it)
            }
        } catch (e:Exception) {
            Timber.e( "LCP renew ${e.message}")
        }

        callback(license)
    }

    fun getDeviceId() : String {
        Timber.i("LCP getDeviceId")
        var deviceId = UUID.randomUUID().toString()
        val prefs = androidContext.getSharedPreferences("org.readium.r2.settings", Context.MODE_PRIVATE)
        deviceId = prefs.getString("lcp_device_id", deviceId)
        prefs.edit().putString("lcp_device_id", deviceId).apply()
        return deviceId
    }

    fun getDeviceName() : String {
        Timber.i("LCP getDeviceName")
        val deviceName = BluetoothAdapter.getDefaultAdapter()

        return deviceName?.name?.let  {
            return@let it
        }?: run {
            return@run "Android Unknown"
        }

    }

    fun getStatus() : StatusDocument.Status? {
        Timber.i("LCP getStatus")
        return status?.status
    }

    fun fetchPublication(): String? {
        Timber.i("LCP fetchPublication")
        val publicationLink = license.link("publication")
        publicationLink?.let {
            return lcpHttpService.publicationUrl(androidContext, publicationLink.href.toString()).get()
        }
        return null
    }

    fun updateLicenseDocument() {
        Timber.i("LCP updateLicenseDocument")
        if (status != null) {
            val licenseLink = status!!.link("license")

            val latestUpdate = status?.updated?.license
            val lastUpdateDB = database.licenses.dateOfLastUpdate(license.id)

            lastUpdateDB?.let {
                if ((lastUpdateDB.isAfter(latestUpdate)) || (lastUpdateDB.isEqual(latestUpdate))) return
            }

            license = lcpHttpService.fetchUpdatedLicense(licenseLink?.href.toString()) as LicenseDocument
            Timber.i( "LCP  ${license.json}")

            database.licenses.updateLicense(license, status!!.status.toString())
        }
    }

    private fun getData(file: String, url: URL) : ByteArray {
        Timber.i("LCP getData")
        val archive = try {
            ZipFile(url.path)
        } catch (e: Exception){
            throw Exception(LcpError().errorDescription(LcpErrorCase.archive))
        }
        val entry = try {
            archive.getEntry(file)
        } catch (e: Exception){
            throw Exception(LcpError().errorDescription(LcpErrorCase.fileNotInArchive))
        }

        return archive.getInputStream(entry).readBytes()
    }

    fun moveLicense(archivePath: String, licenseURL: URL) {
        Timber.i("LCP moveLicense")
        val source = File(archivePath)
        val tmpZip = File("$archivePath.tmp")
        tmpZip.delete()
        source.copyTo(tmpZip)
        source.delete()
        if (ZipUtil.containsEntry(tmpZip, lcplFilePath)) {
            ZipUtil.removeEntry(tmpZip, lcplFilePath)
        }
        ZipUtil.addEntry(tmpZip, lcplFilePath,  licenseURL.openStream().readBytes(),  source)
        tmpZip.delete()
    }

    fun moveLicense(archivePath: String, licenseData: ByteArray) {
        Timber.i("LCP moveLicense")
        val source = File(archivePath)
        val tmpZip = File("$archivePath.tmp")
        tmpZip.delete()
        source.copyTo(tmpZip)
        source.delete()
        if (ZipUtil.containsEntry(tmpZip, lcplFilePath)) {
            ZipUtil.removeEntry(tmpZip, lcplFilePath)
        }
        ZipUtil.addEntry(tmpZip, lcplFilePath,  licenseData,  source)
        tmpZip.delete()
    }


    override fun currentStatus(): String {
        Timber.i("LCP currentStatus")
        return status?.status.toString()
    }

    override fun lastUpdate(): Date {
        Timber.i("LCP lastUpdate")
        return DateTime(license.dateOfLastUpdate()).toDate()
    }

    override fun issued(): Date {
        Timber.i("LCP issued")
        return DateTime(license.issued).toDate()
    }

    override fun provider(): URL {
        Timber.i("LCP provider")
        return license.provider
    }

    override fun rightsEnd(): Date? {
        Timber.i("LCP rightsEnd")
        return license.rights?.end?.toDate()
    }

    override fun potentialRightsEnd(): Date? {
        Timber.i("LCP potentialRightsEnd")
        return license.rights?.potentialEnd?.toDate()
    }

    override fun rightsStart(): Date? {
        Timber.i("LCP rightsStart")
        return license.rights?.start?.toDate()
    }

    override fun rightsPrints(): Int? {
        Timber.i("LCP rightsPrints")
        return license.rights?.print
    }

    override fun rightsCopies(): Int? {
        Timber.i("LCP rightsCopies")
        return license.rights?.copy
    }

}

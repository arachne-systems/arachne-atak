package dev.arachne.atak

import android.content.Context
import android.os.Bundle
import com.atakmap.android.filesharing.android.service.AndroidFileInfo
import com.atakmap.android.filesharing.android.service.FileInfoPersistanceHelper
import com.atakmap.android.http.rest.NetworkOperationManager
import com.atakmap.android.http.rest.operation.NetworkOperation
import com.atakmap.android.maps.MapView
import com.atakmap.android.missionpackage.MissionPackageMapComponent
import com.atakmap.android.missionpackage.MissionPackageReceiver
import com.atakmap.android.missionpackage.file.MissionPackageExtractorFactory
import com.atakmap.android.missionpackage.http.MissionPackageDownloader
import com.atakmap.android.missionpackage.http.rest.FileTransferRequest
import com.atakmap.android.missionpackage.http.rest.GetFileTransferOperation
import com.atakmap.android.missionpackage.http.rest.PostMissionPackageOperation
import com.atakmap.android.missionpackage.http.rest.PostMissionPackageRequest
import com.atakmap.android.missionpackage.http.rest.QueryMissionPackageOperation
import com.atakmap.android.missionpackage.http.rest.QueryMissionPackageRequest
import com.atakmap.comms.http.TakHttpClient
import com.foxykeep.datadroid.exception.ConnectionException
import com.foxykeep.datadroid.requestmanager.Request
import com.foxykeep.datadroid.service.RequestService.Operation
import com.atakmap.coremap.filesystem.FileSystemUtils
import java.io.File
import java.lang.reflect.Proxy
import java.util.UUID

/** ATAK 5.6+ compatibility seam. Only exact, live Arachne stream keys are routed.
 * Native UI, request callbacks and extraction remain ATAK-owned. Other servers
 * execute their original operations. No global port/provider/settings changes.
 *
 * The public registry keys operations by class name AND handler class loader.
 * A host-loader Java proxy preserves that key; a plugin-loader handler would
 * register a different operation. Check ownership before replacing/restoring.
 * This must be qualified again when ATAK changes its operation contract. */
internal object NativePackageRouting {
    private data class Hook(val id: Int, val name: String, val original: Operation, val handler: Operation)
    private val routes = mutableMapOf<String, LocalTakHttp>()
    private val hooks = mutableListOf<Hook>()
    private val downloads = Any()
    private val hashPattern = Regex("[a-f0-9]{64}")

    @Synchronized fun open(service: LocalTakHttp): AutoCloseable {
        check(!routes.containsKey(service.connection)) { "Native package route already owned" }
        if (hooks.isEmpty()) install()
        check(hooks.all { NetworkOperationManager.getOperation(it.id) === it.handler }) { "Native package dispatcher changed" }
        routes[service.connection] = service
        return AutoCloseable { synchronized(this) {
            routes.remove(service.connection, service)
            if (routes.isEmpty()) restore()
        } }
    }

    private fun install() = synchronized(NetworkOperationManager::class.java) {
        val operations = listOf(
            MissionPackageDownloader.REQUEST_TYPE_QUERY_MISSIONPACKAGE to QueryMissionPackageOperation::class.java,
            MissionPackageDownloader.REQUEST_TYPE_POST_MISSIONPACKAGE to PostMissionPackageOperation::class.java,
            MissionPackageDownloader.REQUEST_TYPE_FILETRANSFER_GET_FILE to GetFileTransferOperation::class.java)
        try {
            for ((id, type) in operations) {
                val original = checkNotNull(NetworkOperationManager.getOperation(id))
                check(original.javaClass == type && NetworkOperationManager.getClass(id) == type.name) { "Unsupported native package operation" }
                val handler = Proxy.newProxyInstance(original.javaClass.classLoader, arrayOf(Operation::class.java)) { proxy, method, args ->
                    when (method.name) {
                        "execute" -> execute(args!![0] as Context, args[1] as Request, original)
                        "equals" -> proxy === args?.get(0)
                        "hashCode" -> System.identityHashCode(proxy)
                        "toString" -> "Arachne scoped ${type.simpleName}"
                        else -> error("Unsupported native operation method")
                    }
                } as Operation
                check(NetworkOperationManager.register(type.name, handler) == id) { "ATAK operation registration contract changed" }
                hooks.add(Hook(id, type.name, original, handler))
            }
        } catch (error: Exception) { restore(); throw error }
    }

    private fun restore() = synchronized(NetworkOperationManager::class.java) {
        for (hook in hooks.asReversed()) {
            if (NetworkOperationManager.getOperation(hook.id) === hook.handler)
                check(NetworkOperationManager.register(hook.name, hook.original) == hook.id)
        }
        hooks.clear()
    }

    private fun execute(context: Context, request: Request, original: Operation): Bundle {
        val query = if (request.requestType == MissionPackageDownloader.REQUEST_TYPE_QUERY_MISSIONPACKAGE)
            request.getParcelable(QueryMissionPackageOperation.PARAM_QUERY) as? QueryMissionPackageRequest else null
        val post = if (request.requestType == MissionPackageDownloader.REQUEST_TYPE_POST_MISSIONPACKAGE)
            request.getParcelable(PostMissionPackageOperation.PARAM_POSTFILE) as? PostMissionPackageRequest else null
        val get = if (request.requestType == MissionPackageDownloader.REQUEST_TYPE_FILETRANSFER_GET_FILE)
            request.getParcelable(GetFileTransferOperation.PARAM_GETFILE) as? FileTransferRequest else null
        val key = query?.serverConnectString ?: post?.serverConnectString ?: get?.fileTransfer?.connectString
        val service = synchronized(this) { routes[key] } ?: return original.execute(context, request)
        try {
            val result = when {
                query != null -> query(service, query)
                post != null -> post(service, post)
                get != null -> download(service, get)
                else -> error("Missing package request")
            }
            android.util.Log.i("Arachne", "NATIVE_PACKAGE_ROUTED operation=${original.javaClass.simpleName} port=${service.port}")
            return result.apply { putInt(NetworkOperation.PARAM_STATUSCODE, 200) }
        } catch (error: Exception) {
            android.util.Log.w("Arachne", "NATIVE_PACKAGE_REQUEST_FAILED", error)
            throw ConnectionException("Arachne package operation failed: ${error.message}")
        }
    }

    private fun client(service: LocalTakHttp) = TakHttpClient.GetHttpClient(service.url("/Marti"), service.connection)

    private fun query(service: LocalTakHttp, request: QueryMissionPackageRequest): Bundle {
        require(request.isValid)
        val url = android.net.Uri.parse(service.url("/Marti/sync/search")).buildUpon()
            .appendQueryParameter("keywords", "missionpackage").apply {
                if (request.hasTool()) appendQueryParameter("tool", request.tool)
            }.build().toString()
        val json = client(service).get(url, "resultCount")
        return Bundle().apply {
            putParcelable(QueryMissionPackageOperation.PARAM_QUERY, request)
            putString(QueryMissionPackageOperation.PARAM_JSONLIST, json)
        }
    }

    private fun post(service: LocalTakHttp, request: PostMissionPackageRequest): Bundle {
        require(request.isValid && hashPattern.matches(request.hash))
        val file = File(request.filepath)
        require(file.isFile && file.length() > 0) { "Package file is unavailable or empty." }
        val size = file.length()
        val name = request.name
        require(name.codePointCount(0, name.length) in 1..80 && name.none { it == '\r' || it == '\n' || it == '"' || it == '\\' })
        val boundary = "arachne-${UUID.randomUUID()}"
        val header = "--$boundary\r\nContent-Disposition: form-data; name=\"assetfile\"; filename=\"$name\"\r\nContent-Type: application/zip\r\n\r\n".toByteArray()
        val trailer = "\r\n--$boundary--\r\n".toByteArray()
        val url = android.net.Uri.parse(service.url("/Marti/sync/missionupload")).buildUpon()
            .appendQueryParameter("hash", request.hash).appendQueryParameter("tool", "public").build().toString()
        // The native post helper buffers the entire stream. The
        // existing entity API streams bytes using the same scoped TLS credentials.
        val http = com.atakmap.comms.http.TakHttpClient.GetHttpClient(service.url("/Marti"), service.connection)
        val posted = try {
            java.io.SequenceInputStream(java.util.Collections.enumeration(listOf(
                header.inputStream(), file.inputStream(), trailer.inputStream()
            ))).use { input ->
                val post = org.apache.http.client.methods.HttpPost(url).apply {
                    entity = org.apache.http.entity.InputStreamEntity(input,
                        Math.addExact(size, (header.size + trailer.size).toLong())).apply {
                        setContentType("multipart/form-data; boundary=$boundary")
                    }
                }
                val response = http.execute(post)
                try { response.verifyOk(); response.stringEntity } finally { response.close() }
            }
        } finally { http.shutdown() }
        check(posted == service.url("/Marti/sync/content") + "?hash=${request.hash}") { "Unexpected package upload response" }
        return Bundle().apply {
            putParcelable(PostMissionPackageOperation.PARAM_POSTFILE, request)
            putString(PostMissionPackageOperation.PARAM_POSTEDURL, posted)
        }
    }

    // ponytail: serialize Arachne imports to protect partial files and the native
    // package directory; use per-download locks if measured demand warrants it.
    private fun download(service: LocalTakHttp, request: FileTransferRequest): Bundle = synchronized(downloads) {
        require(request.isValid)
        val transfer = request.fileTransfer
        val hash = transfer.getSHA256(false)
        require(hashPattern.matches(hash))
        val context = MapView.getMapView().context
        // This is an explicit native download, not unsolicited automatic import.
        require(transfer.size > 0) { "Package is empty or has an invalid size." }
        val file = File(context.cacheDir, "arachne-native-${service.host}-$hash.part")
        if (file.length() > transfer.size) check(file.delete())
        val offset = file.length()
        // Even a complete staged copy must still be present in the current,
        // enabled workspace catalog before a user-triggered native import.
        run {
            check(context.cacheDir.usableSpace >= transfer.size - offset + 1024 * 1024) { "Insufficient space for the package." }
            val url = service.url("/Marti/sync/content") + "?hash=$hash&offset=$offset"
            val response = client(service).execute(org.apache.http.client.methods.HttpGet(url))
            try {
                response.verifyOk()
                require(response.contentLength == transfer.size - offset) { "Package length changed." }
                response.body.use { input -> java.io.FileOutputStream(file, true).use { output ->
                    val buffer = ByteArray(8192)
                    var count = offset
                    while (true) {
                        val read = input.read(buffer); if (read < 0) break
                        count += read; require(count <= transfer.size) { "Package exceeds its declared size." }
                        output.write(buffer, 0, read)
                    }
                    output.fd.sync()
                } }
            } finally { response.close() }
        }
        if (!transfer.verify(file)) { file.delete(); error("Package size or SHA-256 verification failed.") }
        var manifest = checkNotNull(MissionPackageExtractorFactory.GetManifest(file)) { "Invalid data package manifest." }
        check(manifest.isValid)
        val instructions = manifest.configuration.importInstructions
        manifest = checkNotNull(MissionPackageExtractorFactory.Extract(context, file, FileSystemUtils.getRoot(), instructions.isImport))
        check(manifest.isValid) { "Native package extraction failed." }
        if (instructions.isDelete) check(file.delete())
        else {
            val directory = File(MissionPackageMapComponent.getInstance().fileIO.missionPackagePath)
            check(directory.isDirectory || directory.mkdirs())
            val saved = File(directory, "arachne-${UUID.randomUUID()}.zip")
            MissionPackageReceiver.addFileToSkip(saved)
            val db = FileInfoPersistanceHelper.instance()
            val info = AndroidFileInfo("", saved, "application/zip", manifest.toXml(true))
            db.insertOrReplace(info, FileInfoPersistanceHelper.TABLETYPE.SAVED)
            check(FileSystemUtils.renameTo(file, saved) && saved.isFile) { "Could not save the downloaded package." }
            info.setUserName(transfer.senderCallsign); info.setUserLabel(transfer.name)
            info.setSizeInBytes(saved.length().toInt()); info.setUpdateTime(saved.lastModified()); info.setSha256sum(hash)
            db.update(info, FileInfoPersistanceHelper.TABLETYPE.SAVED)
            manifest.path = saved.absolutePath
        }
        Bundle().apply {
            putParcelable(GetFileTransferOperation.PARAM_GETFILE, request)
            putParcelable(GetFileTransferOperation.PARAM_MISSION_PACKAGE_MANIFEST, manifest)
        }
    }
}

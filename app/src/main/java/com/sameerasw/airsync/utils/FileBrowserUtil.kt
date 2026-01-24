import android.util.Log
import android.os.Environment
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

object FileBrowserUtil {
    private const val TAG = "FileBrowserUtil"
    private val ROOT_PATH = Environment.getExternalStorageDirectory().absolutePath

    fun listDirectory(path: String?, showHidden: Boolean = false): String {
        var targetPath = if (path.isNullOrBlank()) ROOT_PATH else path
        
        if (!targetPath.startsWith("/")) {
            targetPath = ROOT_PATH + (if (targetPath.startsWith("/")) "" else "/") + targetPath
        }
        
        val directory = File(targetPath)

        if (!directory.exists()) {
            Log.e(TAG, "Directory does not exist: $targetPath")
            return createErrorResponse(targetPath, "Directory does not exist")
        }
        
        if (!directory.isDirectory) {
             Log.e(TAG, "Not a directory: $targetPath")
             return createErrorResponse(targetPath, "Not a directory")
        }

        val items = directory.listFiles()
        if (items == null) {
            Log.e(TAG, "Access denied or I/O error for: $targetPath")
            return createErrorResponse(targetPath, "Access denied. Please ensure 'All Files Access' permission is granted.")
        }
        
        val jsonItems = JSONArray()
        for (file in items.sortedBy { it.name.lowercase() }.sortedByDescending { it.isDirectory }) {
            if (!showHidden && file.name.startsWith(".") && file.name != ".") {
                continue
            }
            val item = JSONObject()
            item.put("name", file.name)
            item.put("isDir", file.isDirectory)
            item.put("size", if (file.isDirectory) 0 else file.length())
            item.put("time", file.lastModified())
            jsonItems.put(item)
        }

        val data = JSONObject()
        data.put("path", targetPath)
        data.put("items", jsonItems)

        val response = JSONObject()
        response.put("type", "browseData")
        response.put("data", data)

        return response.toString()
    }

    private fun createErrorResponse(path: String, message: String): String {
        val data = JSONObject()
        data.put("path", path)
        data.put("error", message)
        data.put("items", JSONArray())

        val response = JSONObject()
        response.put("type", "browseData")
        response.put("data", data)

        return response.toString()
    }
}

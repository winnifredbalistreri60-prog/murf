package com.uzairansar.hermex.agent.embedded

import java.io.File

/** Дерево процессов по /proc/<pid>/stat (приложению видны процессы его uid). */
internal object ProcTree {
    /** ppid из строки /proc/<pid>/stat: поле после "(comm) state". comm может содержать пробелы и скобки. */
    fun parsePpid(stat: String): Int? {
        val close = stat.lastIndexOf(')')
        if (close < 0) return null
        val rest = stat.substring(close + 1).trim().split(' ')
        return rest.getOrNull(1)?.toIntOrNull()
    }

    fun descendants(root: Int, proc: File = File("/proc")): List<Int> {
        val parent = HashMap<Int, Int>()
        proc.listFiles()?.forEach { d ->
            val pid = d.name.toIntOrNull() ?: return@forEach
            val pp = runCatching { parsePpid(File(d, "stat").readText()) }.getOrNull() ?: return@forEach
            parent[pid] = pp
        }
        val children = parent.entries.groupBy({ it.value }, { it.key })
        val out = ArrayList<Int>()
        val queue = ArrayDeque(listOf(root))
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            for (c in children[cur].orEmpty()) if (c != root && c !in out) { out += c; queue += c }
        }
        return out
    }
}

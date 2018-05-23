package avalanche

import java.io.File
import java.util.*
import kotlin.collections.LinkedHashMap

fun main(args: Array<String>) {
    val network = Network(50)
    val n1 = network.nodes[0]
    val c1 = mutableListOf<Transaction>()
    val c2 = mutableListOf<Transaction>()

    repeat(50) {
        val n = network.nodes.shuffled(network.rng).first()
        c1.add(n.onGenerateTx(it))
        if (network.rng.nextDouble() < 0.02) {
            val d = network.rng.nextInt(it)
            println("double spend of $d")
            val n2 = network.nodes.shuffled(network.rng).first()
            c2.add(n2.onGenerateTx(d))
        }

        network.run()

        n1.dumpDag(File("node-0-${String.format("%03d", it)}.dot"))
        println("$it: " + String.format("%.3f", fractionAccepted(n)))
    }

    val conflictSets = (c1 + c2).groupBy { it.data }.filterValues { it.size > 1 }
    conflictSets.forEach { v, txs ->
        val t1 = txs[0]
        val t2 = txs[1]
        val accepted1 = network.nodes.map { it.isAccepted(t1) }
        val accepted2 = network.nodes.map { it.isAccepted(t2) }
        if (accepted1.any  { it } && accepted2.any { it }) {
            println("$v: error, accepted1=$accepted1, accepted2=$accepted2")
        }
    }
}

fun fractionAccepted(n: Node): Double {
    val accepted = n.transactions.values.filter { n.isAccepted(it) }.size
    return accepted.toDouble() / n.transactions.size
}

data class Transaction(
        val id: UUID,
        val data: Int,
        val parents: List<UUID>,
        var chit: Int = 0,
        var confidence: Int = 0) {
    override fun toString(): String {
        return "T(id=${id.toString().take(5)}, data=$data, parents=[${parents.map {it.toString().take(5) }}, chit=$chit, confidence=$confidence)"
    }
}

data class ConflictSet(
        var pref: Transaction,
        var last: Transaction,
        var count: Int,
        var size: Int
)

class Network(size: Int) {
    val rng = Random(23)
    val tx = Transaction(UUID.randomUUID(), -1, emptyList(), 1)
    val nodes = (0..size).map { Node(it, tx.copy(),this, rng) }
    fun run() {
        nodes.shuffled(rng).take(20).forEach { it.avalancheLoop() }
    }
}

class Node(val id: Int, val genesisTx: Transaction, val network: Network, val rng: Random) {

    val alpha = 0.8
    val k by lazy { 5 + network.nodes.size / 100 }
    val beta1 = 5
    val beta2 = 5

    val transactions = LinkedHashMap<UUID, Transaction>(mapOf(genesisTx.id to genesisTx))
    val queried = mutableSetOf<UUID>(genesisTx.id)
    val conflicts = mutableMapOf<Int, ConflictSet>(genesisTx.data to ConflictSet(genesisTx, genesisTx, 0, 1))

    val accepted = mutableSetOf<UUID>(genesisTx.id)
    val parentSets = mutableMapOf<UUID, Set<Transaction>>()

    fun onGenerateTx(data: Int): Transaction {
       val edges = parentSelection()
       val t = Transaction(UUID.randomUUID(), data, edges.map { it.id })
        onReceiveTx(this, t)
        return t
    }

    fun onReceiveTx(sender: Node, tx: Transaction) {
        if (transactions.contains(tx.id)) return

        tx.parents.forEach {
            if (!transactions.contains(it)) {
                val t = sender.fetchTx(it)
                onReceiveTx(sender, t)
            }
        }

        if (!conflicts.containsKey(tx.data)) {
            conflicts[tx.data] = ConflictSet(tx, tx, 0, 1)
        } else {
            conflicts[tx.data]!!.size++
        }

        transactions[tx.id] = tx
    }

    fun fetchTx(id: UUID): Transaction {
        return transactions[id]!!.copy()
    }

    fun onQuery(sender: Node, tx: Transaction): Int {
        onReceiveTx(sender, tx)
        return if (isStronglyPreferred(tx)) 1
               else 0
    }

    fun avalancheLoop() {
        val txs = transactions.values.filterNot { queried.contains(it.id) }
        txs.forEach { tx ->
            val sample = network.nodes.filterNot { it == this }.shuffled(rng).take(k)
            val res = sample.map {
                val txCopy = tx.copy()
                it.onQuery(this, txCopy)
            }.sum()
            if (res >= alpha * k) {
                tx.chit = 1
                // Update the preference for ancestors.
                parentSet(tx).forEach { p ->
                    p.confidence += 1
                    val cs = conflicts[p.data]!!
                    if (p.confidence > cs.pref.confidence) {
                       cs.pref = p
                    }
                    if (tx != cs.last) {
                        cs.last = tx
                        cs.count = 0
                    } else {
                        cs.count++
                    }
                }
            }
            queried.add(tx.id)
        }
    }

    fun isPreferred(tx: Transaction): Boolean {
        return conflicts[tx.data]!!.pref == tx
    }

    fun isStronglyPreferred(tx: Transaction): Boolean {
        return parentSet(tx).map { isPreferred(it) }.all { it }
    }

    fun isAccepted(tx: Transaction): Boolean {
        if (accepted.contains(tx.id)) return true

        val cs = conflicts[tx.data]!!
        val parentsAccepted = tx.parents.map { accepted.contains(it) }.all { it }
        val isAccepted = (parentsAccepted && cs.size == 1 && tx.confidence > beta1) ||
                (cs.pref == tx && cs.count > beta2)
        if (isAccepted) accepted.add(tx.id)
        return isAccepted
    }

    fun parentSet(tx: Transaction): Set<Transaction> {

        if (parentSets.contains(tx.id)) return parentSets[tx.id]!!

        val parents = mutableSetOf<Transaction>()
        var ps = tx.parents.toSet()
        while (ps.isNotEmpty()) {
            ps.forEach {
                if (transactions.contains(it)) parents.add(transactions[it]!!)
            }
            ps = ps.flatMap {
                if (transactions.contains(it)) {
                    transactions[it]!!.parents
                } else {
                    emptyList()
                }
            }.toSet()
        }
        parentSets[tx.id] = parents
        return parents
    }

    fun parentSelection(): List<Transaction> {
        val eps0 = transactions.values.filter { queried.contains(it.id) && isStronglyPreferred(it) }
        val eps1 = eps0.filter { conflicts[it.data]!!.size == 1 || it.confidence > 0 }
        val parents = eps1.flatMap { parentSet(it) }.toSet().filterNot { eps1.contains(it) }
        val fallback = transactions.values.reversed().take(5).shuffled(network.rng).take(2)
        return if (parents.isEmpty()) return fallback else parents
    }

    fun dumpDag(f: File) {
        f.printWriter().use { out ->
            out.println("digraph G {")
            transactions.values.forEach {
                val isAcc = isAccepted(it)
                val color = if (isAcc) "color=lightblue; style=filled;" else ""
                val conflictSetSize = conflicts[it.data]!!.size
                val pref = if (conflictSetSize > 1 && isPreferred(it)) "*" else ""
                val chit = if (queried.contains(it.id)) it.chit.toString() else "?"
                out.println("\"${it.id}\" [$color label=\"${it.data}$pref, $chit, ${it.confidence}\"];")
            }
            transactions.values.forEach {
                it.parents.forEach { p->
                    out.println("\"${it.id}\" -> \"$p\";")
                }
            }
            out.println("}")
        }
    }
}

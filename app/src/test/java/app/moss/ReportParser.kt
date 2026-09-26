package app.moss

/** Reads the tree format written by [LayoutReport.render] back into [SnapNode]s. */
object ReportParser {
    private val LINE = Regex(
        """^( *)(\S+)(?: #(.*?))?(?: desc="([^"]*)")?(?: text="([^"]*)")?(?: state="([^"]*)")?""" +
            """(?: \[([^\]]*)])? \((-?\d+),(-?\d+),(-?\d+),(-?\d+)\)$"""
    )

    private class Draft(val depth: Int, val match: MatchResult) {
        val children = mutableListOf<Draft>()

        fun build(): SnapNode {
            val g = match.groups
            val flags = g[7]?.value?.split(' ')?.toSet().orEmpty()
            return SnapNode(
                id = g[3]?.value, // older reports print an empty id as a bare "#"
                className = g[2]!!.value,
                desc = g[4]?.value,
                text = g[5]?.value,
                state = g[6]?.value,
                box = Box(g[8]!!.value.toInt(), g[9]!!.value.toInt(), g[10]!!.value.toInt(), g[11]!!.value.toInt()),
                selected = "sel" in flags,
                clickable = "clk" in flags,
                scrollable = "scr" in flags,
                visible = "hidden" !in flags,
                children = children.map { it.build() },
            )
        }
    }

    fun parse(text: String): SnapNode {
        val roots = mutableListOf<Draft>()
        val stack = ArrayDeque<Draft>()
        for (line in text.lines()) {
            if (line.isBlank() || line.startsWith("===")) continue
            val match = LINE.matchEntire(line) ?: error("Can't parse report line: $line")
            val draft = Draft(match.groupValues[1].length / 2, match)
            while (stack.isNotEmpty() && stack.last().depth >= draft.depth) stack.removeLast()
            if (stack.isEmpty()) roots += draft else stack.last().children += draft
            stack.addLast(draft)
        }
        return roots.single().build()
    }

    fun fixture(name: String): String =
        ReportParser::class.java.getResource("/$name")?.readText() ?: error("Missing fixture $name")
}

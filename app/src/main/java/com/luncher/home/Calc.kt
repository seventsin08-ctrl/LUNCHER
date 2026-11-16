package com.luncher.home

/** Tiny calculator for Spotlight-style search: + - * / % ^ and parentheses. */
object Calc {
    fun looksLikeMath(s: String): Boolean {
        val t = s.replace(" ", "")
        return t.length >= 3 && t.any { it in "+-*/x×÷^%" } && t.any { it.isDigit() } &&
            t.all { it.isDigit() || it in "+-*/x×÷^%().," }
    }

    fun eval(input: String): Double? = runCatching {
        val parser = Parser(
            input.replace(" ", "").replace('x', '*').replace('×', '*').replace('÷', '/').replace(',', '.')
        )
        val v = parser.expr()
        if (parser.pos != parser.s.length) null else v
    }.getOrNull()?.takeIf { !it.isNaN() && !it.isInfinite() }

    fun format(v: Double): String =
        if (v == Math.floor(v) && kotlin.math.abs(v) < 1e15) v.toLong().toString()
        else "%.8f".format(v).trimEnd('0').trimEnd('.')

    private class Parser(val s: String) {
        var pos = 0
        private fun peek(): Char? = s.getOrNull(pos)

        fun expr(): Double {
            var v = term()
            while (true) {
                when (peek()) {
                    '+' -> { pos++; v += term() }
                    '-' -> { pos++; v -= term() }
                    else -> return v
                }
            }
        }

        private fun term(): Double {
            var v = power()
            while (true) {
                when (peek()) {
                    '*' -> { pos++; v *= power() }
                    '/' -> { pos++; v /= power() }
                    '%' -> { pos++; v %= power() }
                    else -> return v
                }
            }
        }

        private fun power(): Double {
            val b = unary()
            if (peek() == '^') {
                pos++
                return Math.pow(b, power())
            }
            return b
        }

        private fun unary(): Double {
            if (peek() == '-') { pos++; return -unary() }
            if (peek() == '+') { pos++; return unary() }
            return atom()
        }

        private fun atom(): Double {
            if (peek() == '(') {
                pos++
                val v = expr()
                if (peek() != ')') throw IllegalArgumentException("missing )")
                pos++
                return v
            }
            val start = pos
            while (peek()?.let { it.isDigit() || it == '.' } == true) pos++
            if (start == pos) throw IllegalArgumentException("number expected")
            return s.substring(start, pos).toDouble()
        }
    }
}

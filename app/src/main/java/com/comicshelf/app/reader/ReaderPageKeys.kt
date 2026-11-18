package com.comicshelf.app.reader

import android.view.KeyEvent

/** Samsung Air Actions sends these keys to the Activity, without requiring reader focus. */
internal class ReaderPageKeys {
    private var turn: ((Int) -> Unit)? = null

    fun attach(onTurn: (Int) -> Unit): () -> Unit {
        turn = onTurn
        return { if (turn === onTurn) turn = null }
    }

    fun onKeyDown(keyCode: Int, repeatCount: Int): Boolean {
        val onTurn = turn ?: return false
        val delta = when (keyCode) {
            KeyEvent.KEYCODE_F9 -> 1
            KeyEvent.KEYCODE_F10 -> -1
            else -> return false
        }
        if (repeatCount == 0) onTurn(delta)
        return true
    }
}

internal fun pageTurnTarget(current: Int, count: Int, delta: Int): Int? =
    (current + delta).takeIf { it in 0 until count }

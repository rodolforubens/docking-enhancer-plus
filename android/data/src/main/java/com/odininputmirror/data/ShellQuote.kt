package com.odininputmirror.data

/** Single-quotes a string for safe interpolation into a shell command. */
internal fun String.shellQuote(): String = "'${replace("'", "'\"'\"'")}'"

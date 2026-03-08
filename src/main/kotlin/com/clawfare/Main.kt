package com.clawfare

import com.clawfare.cli.ClawfareCommand
import picocli.CommandLine
import kotlin.system.exitProcess

/**
 * Entry point for clawfare CLI application.
 */
fun main(args: Array<String>) {
    val exitCode = CommandLine(ClawfareCommand()).execute(*args)
    exitProcess(exitCode)
}

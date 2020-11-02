#!/usr/bin/env kotlin
@file:DependsOn("com.github.ajalt.clikt:clikt-jvm:3.0.1")

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.output.TermUi

class App : CliktCommand() {

  enum class DaemonType {
    Gradle, Kotlin
  }

  data class Version(
    val major: Int,
    val minor: Int,
    val patch: Int?
  ) : Comparable<Version> {

    override fun compareTo(other: Version): Int {
      return compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })
    }
  }

  private fun String.toVersion(): Version {
    val numbers = split(".").map { it.toInt() }
    val major = numbers[0]
    val minor = numbers[1]
    val patch = numbers.getOrNull(2)
    return Version(major, minor, patch)
  }

  data class Daemon(
    val type: DaemonType,
    val processId: Int,
    val version: String
  )

  override fun run() {
    val daemons = Runtime.getRuntime().exec("jps -mlvV").inputStream.bufferedReader()
      .readLines()
      .filter { line -> line.contains("gradle") || line.contains("kotlin") }
      .mapNotNull { line ->
        parseDaemon(line)
      }
      .groupBy { it.type }
      .mapValues { (_, daemons) ->
        daemons.sortedByDescending { daemon -> daemon.version }
      }
      .flatMap { it.value }

    if (daemons.isEmpty()) {
      TermUi.echo("No daemons detected.")
      return
    }

    val promptItems = daemons
      .mapIndexed { index, daemon ->
        "[${index + 1}]\t${daemon.type}\t${daemon.version}"
      }
      .joinToString(separator = "\n")

    TermUi.echo(promptItems)

    val outdatedDaemons = outdatedDaemons(daemons)
    val preFill = if (outdatedDaemons.isEmpty()) {
      null
    } else {
      daemons.mapIndexedNotNull { index, daemon ->
        if (daemon in outdatedDaemons) {
          index + 1
        } else {
          null
        }
      }.joinToString(separator = ",")
    }
    val daemonsToKill = TermUi.prompt("Enter the numbers, separated by commas you want to kill", default = preFill) { input ->
      input.split(",").map { rawNumber ->
        val index = (rawNumber.toIntOrNull() ?: -1) - 1
        daemons.getOrElse(index) {
          throw UsageError("Invalid input")
        }
      }
    }!!

    daemonsToKill.forEach { daemon ->
      val command = if (TermUi.isWindows) {
        "taskkill /F /PID ${daemon.processId}"
      } else {
        "kill -9 ${daemon.processId}"
      }
      Runtime.getRuntime().exec(command)
      TermUi.echo("killed ${daemon.type}\t${daemon.version}")
    }
  }

  private fun parseDaemon(line: String): Daemon? {
    val processId = "(.*?) ".toRegex().find(line)!!.groupValues.drop(1).first().toInt()
    parseGradleVersion(line)?.let { gradleVersion ->
      return Daemon(type = DaemonType.Gradle, version = gradleVersion, processId = processId)
    }
    parseKotlinVersion(line)?.let { kotlinVersion ->
      return Daemon(type = DaemonType.Kotlin, version = kotlinVersion, processId = processId)
    }
    return null
  }

  private fun parseKotlinVersion(line: String): String? {
    return "kotlin-compiler-embeddable-(.*?)\\.jar".toRegex().find(line)?.groupValues?.drop(1)
      ?.firstOrNull()
  }

  private fun parseGradleVersion(line: String): String? {
    return "org.gradle.launcher.daemon.bootstrap.GradleDaemon (.*?) ".toRegex()
      .find(line)?.groupValues?.drop(1)?.firstOrNull()
  }

  private fun outdatedDaemons(daemons: List<Daemon>): List<Daemon> {
    return daemons.groupBy { it.type }
      .map { (_, daemons) ->
        val largestVersion = daemons.maxByOrNull { it.version }
          ?.version
        if (daemons.size > 1) {
          daemons.filter { it.version != largestVersion }
        } else {
          emptyList()
        }
      }
      .flatten()
  }
}

App().main(args)

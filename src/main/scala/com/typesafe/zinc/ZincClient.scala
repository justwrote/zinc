/**
 * Copyright (C) 2012 Typesafe, Inc. <http://www.typesafe.com>
 */

package com.typesafe.zinc

import com.martiansoftware.nailgun.NGConstants
import java.io.{ ByteArrayOutputStream, DataInputStream, File, OutputStream }
import java.net.{ InetAddress, Socket }
import java.nio.ByteBuffer
import java.util.{ List => JList }
import scala.annotation.tailrec
import scala.collection.JavaConverters._

object ZincClient {
  object Chunk {
    val Argument  = NGConstants.CHUNKTYPE_ARGUMENT
    val Command   = NGConstants.CHUNKTYPE_COMMAND
    val Directory = NGConstants.CHUNKTYPE_WORKINGDIRECTORY
    val StdOut    = NGConstants.CHUNKTYPE_STDOUT
    val StdErr    = NGConstants.CHUNKTYPE_STDERR
    val Exit      = NGConstants.CHUNKTYPE_EXIT
  }

  object Exception {
    val ServerExit    = NGConstants.EXIT_EXCEPTION
    val NoSuchCommand = NGConstants.EXIT_NOSUCHCOMMAND
    val ClientReceive = 897
  }
}

/**
 * Client for talking directly to a nailgun server from another JVM.
 */
class ZincClient(val address: InetAddress, val port: Int) {
  def this(address: String, port: Int) = this(InetAddress.getByName(address), port)
  def this(port: Int) = this(InetAddress.getByName(null), port)

  /**
   * Send a zinc command to a currently running nailgun server.
   * All output goes to specified output streams. Exit code is returned.
   * @throws java.net.ConnectException if the zinc server is not available
   */
  @throws(classOf[java.net.ConnectException])
  def run(args: Seq[String], cwd: File, out: OutputStream, err: OutputStream): Int =
    send("zinc", args, cwd, out, err)

  /**
   * Java API for sending a zinc command to a currently running nailgun server.
   * All output goes to specified output streams. Exit code is returned.
   * @throws java.net.ConnectException if the zinc server is not available
   */
  def run(args: JList[String], cwd: File, out: OutputStream, err: OutputStream): Int =
    send("zinc", args.asScala, cwd, out, err)

  /**
   * Send a command to a currently running nailgun server.
   * Possible commands are "zinc", "status", and "shutdown".
   * All output goes to specified output streams. Exit code is returned.
   * @throws java.net.ConnectException if the zinc server is not available
   */
  def send(command: String, args: Seq[String], cwd: File, out: OutputStream, err: OutputStream): Int = {
    val sendCmdFn: (String, Seq[String], OutputStream) => Unit = sendCommand(_, _, cwd, _)
    send(command, args, sendCmdFn, out, err)
  }

  private def send(command: String, args: Seq[String], sendCmdFn: (String, Seq[String], OutputStream) => Unit, out: OutputStream, err: OutputStream): Int = {
    val socket = new Socket(address, port)
    val sockout = socket.getOutputStream
    val sockin = new DataInputStream(socket.getInputStream)
    sendCmdFn(command, args, sockout)
    val exitCode = receiveOutput(sockin, out, err)
    sockout.close(); sockin.close(); socket.close()
    exitCode
  }

  /**
   * Check if a nailgun server is currently available.
   */
  def serverAvailable(): Boolean = try {
    val out = new ByteArrayOutputStream
    val err = new ByteArrayOutputStream
    val sendCmdFn: (String, Seq[String], OutputStream) => Unit = sendCommand
    val exitCode = send("ng-version", Seq.empty, sendCmdFn, out, err)
    exitCode == 0
  } catch {
    case _: java.io.IOException => false
  }

  private def filterValidArguments(args: Seq[String]): Seq[String] = {
    @tailrec
    def filter(validArgs: Seq[String], args: Seq[String]): Seq[String] = args match {
      case Seq(x, xs@_*) if x.startsWith("-") =>
        val (values, tail) = xs span { s => !s.startsWith("-") }
        if(values.isEmpty)
          filter(x +: validArgs, xs)
        else {
          val validValues = values filterNot { _.trim.isEmpty }
          if(validValues.isEmpty) {
            filter(validArgs, tail)
          } else {
            filter((x +: validValues).reverse ++: validArgs, tail)
          }
        }
      case Seq(x, xs@_*) if x.trim.isEmpty => filter(validArgs, xs)
      case Seq(x, xs@_*) => filter(x +: validArgs, xs)
      case Seq() => validArgs
    }
    filter(Seq.empty, args).reverse
  }

  private def sendArguments(args: Seq[String], out: OutputStream): Unit = {
    import ZincClient.Chunk.Argument
    val validArgs = filterValidArguments(args)
    validArgs foreach { arg => putChunk(Argument, arg, out) }
  }

  private def sendCommand(command: String, args: Seq[String], out: OutputStream): Unit = {
    import ZincClient.Chunk.Command
    sendArguments(args, out)
    putChunk(Command, command, out)
  }

  private def sendCommand(command: String, args: Seq[String], cwd: File, out: OutputStream): Unit = {
    import ZincClient.Chunk.{ Command, Directory }
    sendArguments(args, out)
    putChunk(Directory, cwd.getCanonicalPath, out)
    putChunk(Command, command, out)
  }

  @tailrec
  private def receiveOutput(in: DataInputStream, out: OutputStream, err: OutputStream): Int = {
    import ZincClient.Chunk.{ Exit, StdOut, StdErr }
    val exitCode =
      try {
        val (chunkType, data) = getChunk(in)
        chunkType match {
          case Exit => Some(new String(data).toInt)
          case StdOut => out.write(data); None
          case StdErr => err.write(data); None
        }
      } catch {
        case _: Exception => Some(ZincClient.Exception.ClientReceive)
      }
    if (exitCode.isDefined) exitCode.get else receiveOutput(in, out, err)
  }

  private def createHeader(size: Int, chunkType: Char): Array[Byte] = {
    ByteBuffer.allocate(5).putInt(size).put(chunkType.toByte).array
  }

  private def readHeader(array: Array[Byte]): (Int, Char) = {
    val buffer = ByteBuffer.wrap(array, 0, 5)
    (buffer.getInt, buffer.get.toChar)
  }

  private def putChunk(chunkType: Char, data: String, output: OutputStream): Unit = {
    output.write(createHeader(data.length, chunkType))
    output.write(data.getBytes)
  }

  private def getChunk(input: DataInputStream): (Char, Array[Byte]) = {
    val header = Array.ofDim[Byte](5)
    input.readFully(header)
    val (size, chunkType) = readHeader(header)
    val data = Array.ofDim[Byte](size)
    input.readFully(data)
    (chunkType, data)
  }
}

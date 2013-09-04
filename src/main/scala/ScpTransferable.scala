package com.github.philcali.aws

import com.decodified.scalassh.SshClient

import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.xfer.scp.SCPFileTransfer
import net.schmizz.sshj.xfer.TransferListener
import net.schmizz.sshj.xfer.LoggingTransferListener

/**
 * Extends an SshClient with file transferable abilities
 */
trait ScpTransferable {
  self: SshClient =>

  /**
   * Performs some body on a SFTP client
   *
   * @param fun (SFTPClient =&gt; T)
   * @return Either[String, T]
   */
  def withFileClient[T](fun: SFTPClient => T) = {
    authenticatedClient.right.flatMap {
      client =>
      startSession(client).right.flatMap {
        session =>
        protect("SFTP client failed") {
          fun(client.newSFTPClient())
        }
      }
    }
  }

  /**
   * Performs some body on a SCP transfer channel
   *
   * @param fun (SCPFileTransfer =&gt; Unit)
   * @param listener TransferListener
   * @return Either[String, ScpTransferable]
   */
  def withFileTransfer(fun: SCPFileTransfer => Unit)(listener: TransferListener) = {
    authenticatedClient.right.flatMap {
      client =>
      startSession(client).right.flatMap {
        session =>
        protect("SCP file transfer") {
          val transfer = client.newSCPFileTransfer()
          transfer.setTransferListener(listener)
          fun(transfer)
          this
        }
      }
    }
  }

  /**
   * Uploads a file to a remote machine
   *
   * @param localPath String
   * @param remotePath String
   * @return Either[String, ScpTransferable]
   */
  def upload(localPath: String, remotePath: String)(implicit listener: TransferListener = new LoggingTransferListener()) = {
    withFileTransfer(_.upload(localPath, remotePath))(listener)
  }

  /**
   * Downloads a file from remote machine to a local path
   *
   * @param remotePath String
   * @param localPath String
   * @return Either[String, ScpTransferable]
   */
  def download(remotePath: String, localPath: String)(implicit listener: TransferListener = new LoggingTransferListener()) = {
    withFileTransfer(_.download(remotePath, localPath))(listener)
  }
}

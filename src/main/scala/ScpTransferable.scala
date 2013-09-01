package com.github.philcali.aws

import com.decodified.scalassh.SshClient

import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.xfer.scp.SCPFileTransfer

trait ScpTransferable {
  self: SshClient =>

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

  def withFileTransfer(fun: SCPFileTransfer => Unit) = {
    authenticatedClient.right.flatMap {
      client =>
      startSession(client).right.flatMap {
        session =>
        protect("SCP file transfer") {
          fun(client.newSCPFileTransfer())
          this
        }
      }
    }
  }

  def upload(localPath: String, remotePath: String) = {
    withFileTransfer(_.upload(localPath, remotePath))
  }

  def download(remotePath: String, localPath: String) = {
    withFileTransfer(_.download(remotePath, localPath))
  }
}

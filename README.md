# AWS EC2 SBT Plugin

This plugin allows maintaining EC2 environments from inside the sbt console.
The plugin is particularly useful for deploying groups of dependent
cloud servers for tasks like Selenium grid, Akka clusters, Mongo config
servers, etc.

You can specific monitoring for a group, and triggers for when they are "hot".
You can optionally specify a ssh client to run commands on instances.

Essentially, it's possible to build, and deploy to target EC2 environments, via
an sbt build script we know and love.

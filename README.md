# AWS EC2 SBT Plugin

This plugin allows maintaining EC2 environments from inside the sbt console.
The plugin is particularly useful for deploying groups of dependent
cloud servers for tasks like Selenium grid, Akka clusters, Mongo config
servers, etc.

You can specify monitoring for a group, and triggers for when they are "hot".
You can optionally specify a ssh client to run commands on instances.

Essentially, it's possible to build, and deploy to target EC2 environments, via
an sbt build script we know and love.

## Installation

__build.sbt__

```
addSbtPlugin("com.github.philcali" % "sbt-aws-plugin" % "0.1.0")
```

or via git uri:

```
lazy val root = project.in( file(".") ).dependsOn( awsPlugin )
lazy val awsPlugin = uri("git://github.com/philcali/sbt-aws-plugin")
```

Additional plugin information (like global plugins, etc) can be found
at the [sbt plugin documentation][1].

## Requirements

- Some accessible [Mongo DB][2]
- [Amazon AWS account][3], with API access

## Plugin Structure

At this point in the plugin's life, the main points are:

- Filter AMI's into logical groups
- Create instances from those AMI's, with customizable groups and types
- Alert when instances are ready to be operated one
- Customize an SSH client for an instance
- Terminate the logical groups

Logical groups are _named_ describeImageRequests, or
`NamedAwsRequest`s. These are added via the `aws.ec2.requests` key.

The ability to create environment from these logical groups is
crucial, and that's where `aws.ec2.actions` comes into play. This is a
collection of `NamedAwsAction`s.

Built-in actions are:

- `test`: Dry runs the request with your filters to "test" the result
- `create`: Creates instances from the logical group
- `alert`: Watches created instances from the logical group
- `status`: Checks the status of the group from Amazon
- `terminate`: Terminates the group

Once an instance is created, the `aws.ec2.created` callback is invoked.
This callback is particularly useful for mapping elastic IP
addresses, monitors, and status checks to newly created instances
in the logical group.

Once instances are denoted to being _hot_, the `aws.ec2.finished`
callback is invoked. This callback is far more useful as it might
involve organized coupling of two or more logical groups.

Finally, there's the `NamedSshScript`. This is an optional setup, but
enhances the plugin's automated capabilities with the ability to
execute remote commands and file transfers, via ssh and Scala code.

## Ideal Setup

- install sbt-aws-plugin as a global sbt plugin
- create a `aws.sbt` file in your `~/.sbt/{version}/`, containing your aws credentials, `aws.key` and `aws.secret`
- create a `ssh.sbt` file in your `~/.sbt/{version}/`, containing your ssh public key path to your aws pem.

Now you can create an EC2 instance to test deployment, integration, etc, for your Apps.

## Learn by Example

In this example, assume we're building a Selenium test program that
connects to a hub, linked by UI nodes, hitting the application server
where your app is deployed.

It's pretty clear our logical groups here:

- `hub`
- `nodes`
- `app`

Assuming our `aws.sbt` contains the credentials, and `ssh.sbt`
contains the ssh client info, we need to define the image requests.

- `aws-ec2-request/hub.json`

```
{
  "owners": ["your ownerID", "someone else's id?"],
  "filters": [
    { "name": "tag:Type", "value": "Selenium Hub" }
  ]
}
```

- `aws-ec2-request/nodes.json`

```
{
  "owners": ["ownerId"],
  "filters": [
    { "name": "tag:Type", "value": "Selenium Nodes" }
  ]
}
```

- `aws-ec2-request/app.json`

```
{
  "owners": ["ownerId"],
  "filters": [
    { "name": "name", "value": "Java7 App Server" }
  ]
}
```

Now add the requests to the requests:

```
aws.ec2.requests ++= Seq(
  JSONAwsFileRequest("hub", aws.ec2.requestDir.value),
  JSONAwsFileRequest("nodes", aws.ec2.requestDir.value),
  JSONAwsFileRequest("app", aws.ec2.requestDir.value)
)
```

It is recommended to customize the `aws.ec2.configuredInstance` key, to
attach instance size and security group info for the `create` action.

```
aws.ec2.configuredInstance := {
  case ("hub", image) =>
  aws.ec2.defaultRunRequest(image, "m1.small")
    .withMinCount(1)
    .withMaxCount(1)
    .withSecurityGroups("Selenium Grid Server")
  case ("nodes", image) =>
  aws.ec2.defaultRunRequest(image)
    .withMinCount(1)
    .withMaxCount(1)
    .withSecurityGroups("UI Group")
  case ("app", image) =>
  aws.ec2.defaultRunRequest(image, "m1.small")
    .withMinCount(1)
    .withMaxCount(1)
    .withSecurityGroups("App Group")
}
```

At this point, you can now run `awsEc2Run create nodes` in the shell,
and it will create all of the UI node instances. Automatically
creating the instances doesn't give us much if they can't wire
themselves up to one another. In the Selenium grid architecture,
the hub must be running, and nodes connect themselves to it. Let's
create a couple of `NamedSshScript`s to launch the grid and connect
the nodes to it.

```
val seleniumJar = "java -jar selenium-server.jar"

aws.ssh.scripts += NamedSshScript("grid", execute = {
  _.exec(s"${seleniumJar} -role hub")
})

aws.ssh.scripts += NamedSshScript("node", execute = {
  client =>
  val query = MongoDBObject("group" -> "hub")
  aws.mongo.collection.value.findOne(query) match {
    case Some(instance) =>
    val hubUrl = s"http://${instance("publicDns")}:4444/grid/register"
    client.exec(s"${seleniumJar} -role node -hub ${hubUrl}")
    case None => Left("Please create the hub >:(")
  }
})

aws.ssh.scripts += NamedSshScript("deploy", execute = {
  sshClient =>
  val jar = "~/" + (jarName in assembly).value
  val assemblyJar = (outputPath in assembly).value.getAbsolutePath

  sshClient.upload(assemblyJar, jar).right.map {
    _.exec("java -jar " + jar)
  }
})
```

Now that the scripts are in place, we can execute them when the
instance is hot, by tying it in `aws.finished`.

```
aws.ec2.finished := {
  instance =>
  val execute = (script: NamedSshScript =>
    aws.ssh.retry(delay = aws.ec2.pollingInterval.value) {
      aws.ssh.connectScript(instance, aws.ssh.config.value)(script)
    }
  )
  instance.get("group") foreach {
    case "hub" =>
    aws.ssh.scripts.value.find(_.name == "grid") foreach (execute)
    case "nodes" =>
    aws.ssh.scripts.value.find(_.name == "node") foreach (execute)
    case "app" =>
    streams.value.log.info("Instance is running.")
  }
}
```

The finished callbacks will fire upon logical group alert:

```
> awsEc2Run create *
> awsEc2Run alert hub
> awsEc2Run alert nodes
> awsEc2Run alert app
> assembly
> awsSshRun deploy app
```

Assuming that `test-run` will launch the Selenium test suite with an
arg to take in the hub url and app url:

```
> awsEc2Run status app
> awsEc2Run status hub
```

That'll give you the public DNS of the hub and app, respectively.

```
> test-run http://hubPublicDns:4444/wd/hub http://appPublicDns
```

Run it as much as you like until you are ready to destroy the groups.

```
> awsEc2Run terminate app
> awsEc2Run terminate nodes
> awsEc2Run terminate hub
```

Obviously, this process could be a improved a bit if the runner could
access the mongo ec2 instance collection.

[1]: http://www.scala-sbt.org/release/docs/Extending/Plugins
[2]: http://www.mongodb.com/
[3]: http://aws.amazon.com/

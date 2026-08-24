# JPI Sample Plugin

Build the main application into the local Maven repository, then package the plugin:

```powershell
cd ../..
mvn install
cd examples/sample-plugin
mvn package
```

Open `Advanced -> Plugins`, click `Install JAR...`, and select `target/jpi-sample-plugin-1.0.0.jar`.

The sample registers an Advanced tab, a class bytecode analyzer, a text exporter, and a declarative Netty hook profile. The service-provider file under `META-INF/services` is required for discovery.

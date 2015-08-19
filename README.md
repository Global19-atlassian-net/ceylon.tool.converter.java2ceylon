# Java to Ceylon Converter

This is a project to convert java to Ceylon. 
The Main class generates an AST and converts the given java file to Ceylon.
The Main class requires 2 arguments - the location of the source java file and the destination Ceylon file.

## Instructions

###With `ant`

```bash
git clone https://github.com/ceylon/ceylon.tool.converter.java2ceylon.git
cd ceylon.tool.converter.java2ceylon
ant install
```

The buildfile assumes that `ceylon-dist` (including the Ceylon ant files) is a sibling folder; otherwise, you might have to adjust the paths in `build.properties`.

###Usage

If you have the plugin installed,

`ceylon convert 'full/directory/of/java/file.java' 'test.ceylon'`

If you don't have the plugin installed run

`ceylon run ceylon.tool.converter.java2ceylon 'full/directory/of/java/file.java' 'test.ceylon'`

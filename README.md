# elasticsearch简介

​		Elasticsearch是一个基于lucene分布式搜索和分析引擎。

## 索引 index

​		一个索引就是一个拥有几分相似特征的文档的集合。比如说，可以有一个客户数据的索引，另一个产品目录的索引，还有一个订单数据的索引
​		一个索引由一个名字来标识（必须全部是小写字母的），并且当我们要对对应于这个索引中的文档进行索引、搜索、更新和删除的时候，都要使用到这个名字
​		在一个集群中，可以定义任意多的索引。

## 映射 mapping

​		ElasticSearch中的映射（Mapping）用来定义一个文档
​		mapping是处理数据的方式和规则方面做一些限制，如某个字段的数据类型、默认值、分析器、是否被索引等等，这些都是映射里面可以设置的

## 字段Field

​		相当于是数据表的字段，对文档数据根据不同属性进行的分类标识

## 类型 Type

​		每一个字段都应该有一个对应的类型，例如：Text、Keyword、Byte等

## 文档 document

​		一个文档是一个可被索引的基础信息单元。比如，可以拥有某一个客户的文档，某一个产品的一个文档，当然，也可以拥有某个订单的一个文档。文档以JSON（Javascript Object Notation）格式来表示，而JSON是一个到处存在的互联网数据交互格式

# elasticsearch排分插件简介

​		初衷：假设业务方要求有若干业务因子要干扰到排名，同时还不能放弃框架本身的文本相似度评分，那么应该怎么做呢？ 这种场景尤其是在电商类的一些垂直搜索中体现比较明显，比如，商品名称，销量，商品介绍详细等等。

​		把众多的业务因素加到的总的评分里面就要使用自定义的elasticsearch排分插件，可以自由的定制各种策略。

​		本章就来好好分析运用。从0到1实战。

# elasticsearch插件开发全流程

##  1、下载Elasticsearch源码

```
git clone https://github.com/elastic/elasticsearch.git
cd elasticsearch
git branch -a
git checkout 7.1
```

## 2、环境准备

一定要用jdk11以上，我选的是jdk12

![img](https://docimg3.docs.qq.com/image/DJw6wK69y9katyu6rwhPeQ.png?w=1283&h=832)

## 3、修改maven源

http://maven.aliyun.com/nexus/content/groups/public/

### elasticsearch\buildSrc\build.gradle

![img](https://docimg7.docs.qq.com/image/BUb0qWmvkVqI4ED16tFtjg.png?w=869&h=286)

```
/*****************************************************************************
 *                         Bootstrap repositories                            *
 *****************************************************************************/
// this will only happen when buildSrc is built on its own during build init
if (project == rootProject) {
  repositories {
    //if (System.getProperty("repos.mavenLocal") != null) {
      //mavenLocal()
    //}
	maven{
		url 'http://maven.aliyun.com/nexus/content/groups/public/'
	}
  }
  // only run tests as build-tools
  test.enabled = false
}

```

### elasticsearch\distribution\packages\build.gradle

![img](https://docimg9.docs.qq.com/image/e7DqnNt_m5_OoMCZZ5LiSQ.png?w=797&h=250)

```
buildscript {
  repositories {
    maven {
      name "gradle-plugins"
      //url "https://plugins.gradle.org/m2/"
     url "http://maven.aliyun.com/nexus/content/groups/public/"
    }
  }
  dependencies {
    classpath 'com.netflix.nebula:gradle-ospackage-plugin:4.7.1'
  }
}

```

### elasticsearch\plugins\repository-s3\build.gradle

![img](https://docimg2.docs.qq.com/image/Q1c7oFDf2MfZBWi_1P9S6g.png?w=712&h=228)

```
buildscript {
  repositories {
    maven {
      //url 'https://plugins.gradle.org/m2/'
     url 'http://maven.aliyun.com/nexus/content/groups/public/'
    }
  }
  dependencies {
    classpath 'de.undercouch:gradle-download-task:3.4.3'
  }
}
```

## 4、修改用户gradle设置

例如我的：C:\Users\mn\.gradle

```
allprojects{
    repositories {
		def REPOSITORY_URL = 'http://maven.aliyun.com/nexus/content/groups/public/'
        all { ArtifactRepository repo ->
            if(repo instanceof MavenArtifactRepository){
                def url = repo.url.toString()
                if (url.startsWith('https://repo1.maven.org/maven2') || url.startsWith('https://jcenter.bintray.com/')) {
                    project.logger.lifecycle "Repository ${repo.url} replaced by $REPOSITORY_URL."
                    remove repo
                }
            }
        }
        maven {
            url REPOSITORY_URL
        }
    }
}
```

## 5、注释掉运行抛出的异常

因为后面我们会用gradle生成导入elasticsearch项目的ipr，所以这边可以直接进入源码用记事本打开修改，暂时不需要导入工程。

buildSrc\src\main\groovy\org\elasticsearch\gradle\BuildPlugin.groovy

![img](https://docimg7.docs.qq.com/image/JHkTZ158-ll2kA9VHOLbVQ.png?w=1794&h=592)

![img](https://docimg2.docs.qq.com/image/aXd6-ZDk9odS-Uzem-hjng.png?w=1822&h=588)

## 6、elasticsearch源码编译

进入存放源码的文件夹进行编译![img](https://docimg3.docs.qq.com/image/AgAABS4iltB6B31IH6BNrIqngkeBVRi8.png?w=709&h=95)

因为我的已经编译过一次了，所以很快，正常的话是非常慢的。

![img](https://docimg10.docs.qq.com/image/AgAABS4iltAmq7_AZdNE1IU-3pw5vwcp.png?w=1069&h=634)

## 7、经过漫长的等待

经过漫长的等待，会生成一个ipr的文件，我们双击打开导入elasticsearch到idea中。

![img](https://docimg2.docs.qq.com/image/ktIXX5Zous2FHk8ljvKV8A.png?w=1238&h=817)

## 8、Import Gradle Project

点击Event log，然后点击Import Gradle Project

![img](https://docimg10.docs.qq.com/image/VLeL__wzQVXW-Q9Sp0tacQ.png?w=1845&h=1034)

## 9、设置modules

因为我们编译源码运行elasticsearch需要用到一些模块的jar，这些jar不用去别的地方找，直接下载一个发行版本

，将里面的modules文件夹复制到上面配置的-Des.path.home路径下。其实就是复制到elasticsearch源码的distribution下面的modules中。

否则会出现下载的错误。

![img](https://docimg10.docs.qq.com/image/AgAABS4iltDQ-wTrek5Ojaqf08mM1EqK.png?w=1532&h=562)

![img](https://docimg2.docs.qq.com/image/AgAABS4iltBiu4pMGHtBx7pTpmkj3viq.png?w=767&h=822)

## 10、libs模块的类加载问题

如果没有出现忽略，如果出现下面的错误，这个类在libs模块，server模块中原来的gradle配置是compileOnly，改为compile

![img](https://docimg3.docs.qq.com/image/AgAABS4iltCiY_8wNfZHmaDiPl637pPk.png?w=1298&h=512)

![img](https://docimg9.docs.qq.com/image/AgAABS4iltC4LqvGaIlP0KtN0BTlx1rG.png?w=1207&h=702)

## 11、修改权限，必须要做的。

报错提示：

```
org.elasticsearch.bootstrap.StartupException: java.security.AccessControlException: access denied ("java.lang.RuntimePermission" "createClassLoader")
```

找到jdk所在路径：

![img](https://docimg4.docs.qq.com/image/AgAABS4iltC73NMT8NpGgpFBvyA_tnBj.png?w=794&h=777)

找到conf下面的security

![img](https://docimg6.docs.qq.com/image/AgAABS4iltAMcR3x__1EZLirrcLqIGiZ.png?w=1216&h=338)

打开后最后添加

```
permission java.lang.RuntimePermission "getClassLoader";
permission java.lang.RuntimePermission "createClassLoader";
permission java.lang.RuntimePermission "setContextClassLoader";
```

![img](https://docimg6.docs.qq.com/image/AgAABS4iltAbk0u6zutKx74AOfojA_Cs.png?w=968&h=874)

## 12、设置启动项

**1、vm options**

注意这边的地址用你自己的路径。

```
-Des.path.home=E:\github\elasticsearch\elasticsearch-7.1.1\distribution
-Des.path.conf=E:\github\elasticsearch\elasticsearch-7.1.1\distribution\src\config
-Djava.security.policy=E:\github\elasticsearch\elasticsearch-7.1.1\distribution\config\elasticsearch.policy
-Dlog4j2.disable.jmx=true
-Xmx8g
-Xms8g
```

**2、Use classpath of module**
使用elasticsearch下面的server模块的main。

**3、jdk选择12**

**4、main class**

```
org.elasticsearch.bootstrap.Elasticsearch
```

![img](https://docimg4.docs.qq.com/image/AgAABS4iltBBGjyIzolNh6puE3V5E3qS.png?w=1355&h=876)

## 13、启动

至此elasticsearch源码编译成功，并且已经启动。

![img](https://docimg4.docs.qq.com/image/AgAABS4iltDXNwz9bGBOuapw6CVHf4NN.png?w=1872&h=545)

# 安装kibana

有了kibana比较好测试，下载kibana7.1.1版本

修改kibana的conf，并且启动

![img](https://docimg9.docs.qq.com/image/AgAABS4iltBz2cJhHoJMN48S0RDha6Co.png?w=856&h=589)

进入kibana的bin下，点击kibana.bat

![img](https://docimg6.docs.qq.com/image/AgAABS4iltC8VFZePgREH6xvdFBs9EGF.png?w=1390&h=937)

# 测试准备

1、添加索引

我们添加一个索引，索引名称是goods，属性有3个，产品名称productname，所属类别brandname，货物id。

```
PUT goods
{
  "mappings": {
    "properties": {
      "productname": { 
        "type": "text",
        "fielddata":"true"
      },
      "brandname": { 
        "type": "keyword"
      },
      "id": { 
        "type": "keyword"
      }
    }
  }
}
```

2、添加2个货物

```
POST /goods/_doc/1
{
  "id":"1",
  "productname":"联想笔记本",
  "brandname":"联想"
}

POST /goods/_doc/2
{
  "id":"2",
  "productname":"dell笔记本",
  "brandname":"dell"
}
```

# 插件开发测试

## 1、编译

进入plugins\examples\rescore

![img](https://docimg5.docs.qq.com/image/AgAABS4iltC1SvZuHGJM_6PE_gQtSq_o.png?w=1235&h=252)

cmd敲入gradle jar，编译rescore插件为jar

![img](https://docimg7.docs.qq.com/image/AgAABS4iltB7OnYW1vFFBpV-NV8TAb1R.png?w=1162&h=312)

![img](https://docimg1.docs.qq.com/image/AgAABS4iltDPSICWvwlLEoHsQa2ao_uM.png?w=990&h=395)

## 2、结束后生成一个build文件夹

![img](https://docimg6.docs.qq.com/image/AgAABS4iltDeQihM23JF3Joah3a9m1y-.png?w=1033&h=313)

## 3、找到distributions下面的jar

![img](https://docimg6.docs.qq.com/image/AgAABS4iltDWO_lA1sBBt6LvLImrMUbu.png?w=1090&h=190)

## 4、在elasticsearch根目录下

在elasticsearch根目录下找到distributions，进入distributions，再进入pluins，新建example-rescore。

![img](https://docimg4.docs.qq.com/image/AgAABS4iltBpHGXfBmtOq57x30NakWpN.png?w=648&h=179)

## 5、将3步骤中生成的jar放入4步骤中的文件夹中

将3步骤中生成的jar放入4步骤中的文件夹中，同时把-7.1.1-SNAPSHOT去掉。

文件夹下新建文件plugin-descriptor.properties

![img](https://docimg7.docs.qq.com/image/AgAABS4iltCxdCtbV6BIFIlo44fdjxS2.png?w=1077&h=166)

文件内容为：

是描述这个插件的。

```
# Elasticsearch plugin descriptor file
# This file must exist as 'plugin-descriptor.properties' inside a plugin.
#
### example plugin for "foo"
#
# foo.zip <-- zip file for the plugin, with this structure:
# |____   <arbitrary name1>.jar <-- classes, resources, dependencies
# |____   <arbitrary nameN>.jar <-- any number of jars
# |____   plugin-descriptor.properties <-- example contents below:
#
# classname=foo.bar.BazPlugin
# description=My cool plugin
# version=6.0
# elasticsearch.version=6.0
# java.version=1.8
#
### mandatory elements for all plugins:
#
# 'description': simple summary of the plugin
description=An example plugin implementing rescore and verifying that plugins *can* implement rescore
#
# 'version': plugin's version
version=7.1.1-SNAPSHOT
#
# 'name': the plugin name
name=example-rescore
#
# 'classname': the name of the class to load, fully-qualified.
classname=org.elasticsearch.example.rescore.ExampleRescorePlugin
#
# 'java.version': version of java the code is built against
# use the system property java.specification.version
# version string must be a sequence of nonnegative decimal integers
# separated by "."'s and may have leading zeros
java.version=1.8
#
# 'elasticsearch.version': version of elasticsearch compiled against
elasticsearch.version=7.1.1
### optional elements for plugins:
#
#  'extended.plugins': other plugins this plugin extends through SPI
extended.plugins=
#
# 'has.native.controller': whether or not the plugin has a native controller
has.native.controller=false

```

## 6、elasticsearch工程重启下

## 7、加个断点

在此代码上加个断点

E:\github\elasticsearch\elasticsearch-7.1.1\plugins\examples\rescore\src\main\java\org\elasticsearch\example\rescore\ExampleRescoreBuilder.java

![img](https://docimg8.docs.qq.com/image/AgAABS4iltD3XjyMBARAPaBHuem-SpF3.png?w=1673&h=842)

## 8、使用kibana进行查询

```
GET goods/_search
{
    "_source":{
        "includes":["productname","brandname"]
  },
  "query": {
        "multi_match": {
        "query":  "笔记本",
        "fields": [ "productname" ]
    }
  },
  "size":100,
    "rescore":{
        "window_size":10000,
        "example":{
            "factor":10,
            "factor_field":"productname"
        }
        
    }
}
```

## 9、进入断点

说明我们的插件完全已经生效，我们接下去看看如何个性化下实现我们的功能。

![img](https://docimg2.docs.qq.com/image/AgAABS4iltC_pnOJPP9A966yAnGle90Q.png?w=1636&h=822)

## 10、分析

rescore插件下面example有两个参数

1、factor：可以直接传值，未来我们要传值可以参考此项如何传入

2、factor_field：指定的是字段，也就是指定了上面的值影响的字段，未来我们可以通过这个参数传入对应的字段。

3、加入其他字段可以参考上面的2个参数。

## 11、进行个性化

原有的demo没有展示如果通过factor_field进行拿到值，然后进一步处理完成功能。

代码改造：

核心：主要是通过相对位置把值给拿出来，这样有了值以后我们可以根据点击量或者其他你能想到的属性进行自由的改造

```
            if (context.factorField != null) {
                /*
                 * Since this example looks up a single field value it should
                 * access them in docId order because that is the order in
                 * which they are stored on disk and we want reads to be
                 * forwards and close together if possible.
                 *
                 * If accessing multiple fields we'd be better off accessing
                 * them in (reader, field, docId) order because that is the
                 * order they are on disk.
                 */
                ScoreDoc[] sortedByDocId = new ScoreDoc[topDocs.scoreDocs.length];
                System.arraycopy(topDocs.scoreDocs, 0, sortedByDocId, 0, topDocs.scoreDocs.length);
                Arrays.sort(sortedByDocId, (a, b) -> a.doc - b.doc); // Safe because doc ids >= 0
                Iterator<LeafReaderContext> leaves = searcher.getIndexReader().leaves().iterator();
                LeafReaderContext leaf = null;
                SortedNumericDoubleValues data = null;
                int currentReaderIx = -1;
                int endDoc = 0;
                for (int i = 0; i < end; i++) {
                    ScoreDoc hit = sortedByDocId[i];
                    while (hit.doc >= endDoc) {
                        currentReaderIx++;
                        leaf = searcher.getIndexReader().leaves().get(currentReaderIx);
                        endDoc = leaf.docBase + leaf.reader().maxDoc();
                    }
                    //计算相对位置
                    int docId = hit.doc - leaf.docBase;
                    //拿到facotrField指定字段的值
                    SortedBinaryDocValues values_test = ((ExampleRescoreContext) rescoreContext).factorField.load(leaf).getBytesValues();
                    values_test.advanceExact(docId);
                    String productname = "";
                    //内部会有分词，所以通过循环把所有字段内容都拿到
                    for(int docV=0;docV < values_test.docValueCount(); docV++){
                        productname +=values_test.nextValue().utf8ToString();
                    }
                    //输出测试
                    System.out.println("商品名称："+productname+" Id:"+hit.doc + " 得分是：" + hit.score);
                }
            }
```

## 12、输出验证

也就是我们可以通过自己自定义传入的值和字段进行分数的控制，达到影响elasticsearch的召回效果

![img](https://docimg8.docs.qq.com/image/AgAABS4iltBgLvYtOV1PEoqpcRBDX45q.png?w=852&h=198)

# 项目说明

此项目已经进行上面的修改，同时distributions里面也加入了发行版的modules。

如果你自己从源码开始的可以按照上面一步步进行。
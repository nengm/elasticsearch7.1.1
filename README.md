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

![img](https://github.com/nengm/picturebed/blob/main/ELasticsearch/image.png)

## 3、修改maven源

http://maven.aliyun.com/nexus/content/groups/public/

### elasticsearch\buildSrc\build.gradle

![img](https://github.com/nengm/picturebed/blob/main/ELasticsearch/image2.png)

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

![img](https://github.com/nengm/picturebed/blob/main/ELasticsearch/image3.png)

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

![img](https://github.com/nengm/picturebed/blob/main/ELasticsearch/image4.png)

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

![img](https://github.com/nengm/picturebed/blob/main/ELasticsearch/image5.png)

![img](https://github.com/nengm/picturebed/blob/main/ELasticsearch/image6.png)

## 6、elasticsearch源码编译

进入存放源码的文件夹进行编译![img](https://github.com/nengm/picturebed/blob/main/ELasticsearch/image7.png)

因为我的已经编译过一次了，所以很快，正常的话是非常慢的。

![img](https://github.com/nengm/picturebed/blob/main/ELasticsearch/image8.png)

## 7、经过漫长的等待

经过漫长的等待，会生成一个ipr的文件，我们双击打开导入elasticsearch到idea中。

![img](https://github.com/nengm/picturebed/blob/main/ELasticsearch/image9.png)

## 8、Import Gradle Project

点击Event log，然后点击Import Gradle Project

![img](https://github.com/nengm/picturebed/blob/main/ELasticsearch/image10.png)

## 9、设置modules

因为我们编译源码运行elasticsearch需要用到一些模块的jar，这些jar不用去别的地方找，直接下载一个发行版本

，将里面的modules文件夹复制到上面配置的-Des.path.home路径下。其实就是复制到elasticsearch源码的distribution下面的modules中。

否则会出现下载的错误。

![img](https://github.com/nengm/picturebed/blob/main/ELasticsearch/image16.png)

## 10、libs模块的类加载问题

如果没有出现忽略，如果出现下面的错误，这个类在libs模块，server模块中原来的gradle配置是compileOnly，改为compile

![img](https://github.com/nengm/picturebed/blob/main/ELasticsearch/image17.png)

![img](https://github.com/nengm/picturebed/blob/main/ELasticsearch/image18.png)

## 11、修改权限，必须要做的。

报错提示：

```
org.elasticsearch.bootstrap.StartupException: java.security.AccessControlException: access denied ("java.lang.RuntimePermission" "createClassLoader")
```

找到jdk所在路径：

![img](https://github.com/nengm/picturebed/blob/main/ELasticsearch/image19.png)

找到conf下面的security

![img](https://github.com/nengm/picturebed/blob/main/ELasticsearch/image20.png)

打开后最后添加

```
permission java.lang.RuntimePermission "getClassLoader";
permission java.lang.RuntimePermission "createClassLoader";
permission java.lang.RuntimePermission "setContextClassLoader";
```

![img](https://github.com/nengm/picturebed/blob/main/ELasticsearch/image21.png)

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

![img](https://github.com/nengm/picturebed/blob/main/ELasticsearch/image13.png)

## 13、启动

至此elasticsearch源码编译成功，并且已经启动。

![img](https://docimg4.docs.qq.com/image/AgAABS4iltDXNwz9bGBOuapw6CVHf4NN.png?w=1872&h=545)

# 安装kibana

有了kibana比较好测试，下载kibana7.1.1版本

修改kibana的conf，并且启动

![img](https://github.com/nengm/picturebed/blob/main/ELasticsearch/image22.png)

进入kibana的bin下，点击kibana.bat

![img](https://github.com/nengm/picturebed/blob/main/ELasticsearch/image23.png)

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

![img](https://github.com/nengm/picturebed/blob/main/ELasticsearch/image24.png)

cmd敲入gradle assemble，编译rescore插件为jar

![img](https://github.com/nengm/picturebed/blob/main/ELasticsearch/image25.png)

![img](https://github.com/nengm/picturebed/blob/main/ELasticsearch/image26.png)

## 2、结束后生成一个build文件夹

![img](https://docimg6.docs.qq.com/image/AgAABS4iltDeQihM23JF3Joah3a9m1y-.png?w=1033&h=313)

## 3、找到distributions下面的jar

![img](https://docimg6.docs.qq.com/image/AgAABS4iltDWO_lA1sBBt6LvLImrMUbu.png?w=1090&h=190)

## 4、在elasticsearch根目录下

在elasticsearch根目录下找到distributions，进入distributions，再进入pluins，新建example-rescore。

![img](https://docimg4.docs.qq.com/image/AgAABS4iltBpHGXfBmtOq57x30NakWpN.png?w=648&h=179)

## 5、将3步骤中生成的jar放入4步骤中的文件夹中

将3步骤中生成的jar放入4步骤中的文件夹中，同时把-7.1.1-SNAPSHOT去掉。

同时把build\generated-resources下面的plugin-descriptor.properties复制到4中的文件夹中。

![img](https://docimg8.docs.qq.com/image/AgAABS4iltBoE2RxTLVEpb6cwaExtHgF.png?w=842&h=189)



![img](https://docimg7.docs.qq.com/image/AgAABS4iltCxdCtbV6BIFIlo44fdjxS2.png?w=1077&h=166)



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

## 13、继续分析

可以看到我们拿到的并不是商品名称，是经过了一次分词的结果。

如果直接拿到productname，一个方式是把productname设置为keyword，另一个方式是添加个字段productname2，设置为keyword。

## 14、测试

新增索引goods2

```
PUT goods2
{
  "mappings": {
    "properties": {
      "productname": { 
        "type": "text",
        "fielddata":"true"
      },
      "productname2": { 
        "type": "keyword"
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

POST /goods2/_doc/1
{
  "id":"1",
  "productname":"联想笔记本",
  "productname2":"联想笔记本",
  "brandname":"联想"
}

POST /goods2/_doc/2
{
  "id":"2",
  "productname":"dell笔记本",
  "productname2":"dell笔记本",
  "brandname":"dell"
}
```

查询

```
GET goods2/_search
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
            "factor_field":"productname2"
        }
        
    }
}
```

现在就OK了

![img](https://docimg6.docs.qq.com/image/AgAABS4iltDSJylx5LRIKpS3jIf2BDqh.png?w=527&h=168)

当然我还没发现如果调整分词的顺序，如果发现再更新github。

# 项目说明

此项目已经进行上面的修改，同时distributions里面也加入了发行版的modules。

如果你自己从源码开始的可以按照上面一步步进行。
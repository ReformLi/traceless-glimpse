# BrowseGuard Vid 的 R8 规则。
#
# 只写三件事，理由各自不同。写之前把依赖自带的 consumer 规则逐个拆开看过
# （取证过程记在 docs/acceptance-m6-6.md），库里已经覆盖的一律不抄——
# 抄一份进来就等于把库的规则冻结在今天的版本，以后它们修正反而被我们挡住。
#
#   kotlinx-serialization-core 1.12.0
#     META-INF/com.android.tools/r8/kotlinx-serialization-*.pro 已含：
#     @Serializable 类的 static Companion 字段、serializer()、$$serializer.descriptor
#     的 -keepclassmembers，以及 RuntimeVisibleAnnotations/AnnotationDefault 属性。
#   okhttp-android 5.3.2 → aar 内 proguard.txt 已含 conscrypt/bouncycastle/JSR305 的 -dontwarn。
#   room-runtime 2.8.5    → aar 内 proguard.txt 已含 `-keep class * extends androidx.room.RoomDatabase`。
#   kotlinx-coroutines 1.11.0 → 已含 volatile 字段与 MainDispatcherFactory 相关规则。
#   AGP 自带 proguard-android-optimize.txt → 已含 values()/valueOf、@JavascriptInterface、
#     Signature/InnerClasses/*Annotations* 这一整组属性，以及 -dontwarn androidx.**。


# ① 协议模型不参与改名。
#    严格说序列化在 R8 下本来就能跑通：@SerialName 与生成的 descriptor 把线名编译成了
#    字符串常量，改名改不动它们。留这条keep是为了把「三端合同」显式钉在构建配置里——
#    Task / SniffResult / FormatInfo 这些类名一旦在某次升级里被改掉，
#    表现是手机与电脑引擎之间「协议对不上」，而不是编译报错，代价远高于一份 keep 规则。
#    同时 -keep（不是 -keepnames）也顺手挡住了它们被 shrink 掉。
-keep class com.hpu.browseguard.shared.model.** { *; }

# shared/schema 是给 scripts/check_schema.py 导出 JSON Schema 用的，手机运行时不需要，
# 明确放行给 R8 删掉——不然「协议模型必须留」和「导出工具也一起留」会被同一条规则捆住。
-dontnote com.hpu.browseguard.shared.schema.**


# ② Kotlin `when` 过枚举时生成的 $WhenMappings 表。
#    kotlin-stdlib 1.8/1.9/2.0/2.1/2.4 的 jar 里都没有任何 proguard 规则（逐版本展开确认过），
#    AGP 默认文件也只管 values()/valueOf。这张表里的 int 字段是按名字索引的，
#    一旦被改名或与枚举常量错开，症状是「when 落到错误的分支」这种最难查的错。
-keepclassmembers class **$WhenMappings { <fields>; }


# ③ 崩溃可读性，同时不泄露源码文件名。
#    默认规则不带 SourceFile/LineNumberTable，release 崩栈只剩 a.java:1，
#    用户截图回来我这边等于零信息。补上行号；再把源文件名统一改写成 "SourceFile"，
#    这样 APK 里既不携带仓库内的真实路径与文件命名，又保留定位能力
#    （对照表 build/outputs/mapping/release/mapping.txt 在 .gitignore 覆盖范围内，不入库）。
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile


# ─────────────────────────────────────────────────────────────────────────────
# 反射：KeyboardConstants.Keys 的字段名必须原样保留
# ─────────────────────────────────────────────────────────────────────────────
# Keys 里的 Key 常量由两条反射路径读取：
#
#   1. KeyboardConstants.Keys.charToKeyMap —— 遍历 declaredFields 后 field.get() 取值，
#      不依赖字段名（改成什么都能取到）。
#   2. MacroTemplateKeys.byName —— 把 field.name 本身当模板键名（`tap KP_NUM_1`），
#      经 normalize()（trim + 大写 + 去掉下划线/空白）后建查表。
#
# 第 2 条依赖字段名：一旦被 R8 缩短成 a / b / c，`tap KP_NUM_1` 这类模板会**静默**解析失败
# —— 不抛异常，只是按键不生效，很难查。所以这里连名字一起保住。
#
# 注意 Kotlin object 里的 `val X = Key(...)` 编译出来是「private static 字段 + public static
# getter」，反射读到的是那个 private 字段，因此规则里不能限定 public，必须用 <fields>
# 匹配全部字段（<fields> 默认保留名字与存在性，不做混淆）。
-keepclassmembers class com.github.remotecontrol.util.KeyboardConstants$Keys {
    <fields>;
}

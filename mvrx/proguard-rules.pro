# ------------------------
#
#  Mavericks Config
#
# -----------------------
#
# Mavericks creates ViewModels and their initial state with Java reflection. These rules keep only the members it looks up.
# The rules for @PersistState are in proguard-persist-state-rules.pro.

# Mavericks reads the companion factory through Class.getDeclaredClasses(), which needs InnerClasses (and R8 requires
# EnclosingMethod with it), and the @PersistState annotations through Constructor.getParameterAnnotations(). The default Android
# ProGuard files keep these attributes, but an app's own configuration might not.
-keepattributes InnerClasses,EnclosingMethod,RuntimeVisibleParameterAnnotations

# MavericksFactory creates a ViewModel without a companion factory through its constructor that takes only the state. With a
# companion factory whose create() / initialState() are @JvmStatic, it calls the static methods Kotlin generates on the ViewModel.
-keepclassmembers class ** extends com.airbnb.mvrx.MavericksViewModel {
    public <init>(...);
    public static *** create(...);
    public static *** initialState(...);
}

# MavericksViewModelProvider finds the companion factory through Class.getDeclaredClasses() on the ViewModel class, so R8 must not
# move the companion out of it (R8 3.3 full mode did). Both classes can still be renamed, and removed when they are unused.
-keep,allowshrinking,allowobfuscation class **$Companion implements com.airbnb.mvrx.MavericksViewModelFactory
-if class **$Companion implements com.airbnb.mvrx.MavericksViewModelFactory
-keep,allowshrinking,allowobfuscation class <1>

# The companion factory is instantiated through its synthetic constructor that takes a DefaultConstructorMarker, and create() /
# initialState() are looked up by name when they are not @JvmStatic.
-keepclassmembers class ** implements com.airbnb.mvrx.MavericksViewModelFactory {
    public <init>(...);
    public *** create(...);
    public *** initialState(...);
}

# Without a companion initialState(), MavericksStateFactory creates the initial state through the public constructor that takes
# the fragment arguments, or through the public no-argument constructor. PersistStateKt also reads the @PersistState annotations
# from the public primary constructor.
-keepclassmembers class ** implements com.airbnb.mvrx.MavericksState {
    public <init>(...);
}

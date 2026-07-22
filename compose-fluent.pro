# Compose Fluent v0.1.0 was compiled against Compose 1.8.2. Compose 1.11 now
# changed TextManager's action return types. FluentTheme still references the
# old implementation while setting up its composition locals, although KZAgent
# overrides that local with Compose's compatible default before rendering UI.
-dontwarn io.github.composefluent.component.FluentTextContextMenu

# Haze 1.6.6 references the old ShaderBrush bridge only for shader-backed
# masks. Compose Fluent's Mica/Layer usage in KZAgent does not use that path.
-dontwarn dev.chrisbanes.haze.RenderEffect_skikoKt

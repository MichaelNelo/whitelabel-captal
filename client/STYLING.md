# CSS Variables - Theming Reference

## Colors

| Variable | Description |
|----------|-------------|
| `--color-primary` | Brand color principal |
| `--color-primary-dark` | Variante oscura para hover/gradients |
| `--color-primary-light` | Variante clara para acentos |
| `--color-success` | Confirmaciones y checkmarks |
| `--color-error` | Errores y alertas |
| `--color-warning` | Advertencias |

## Background

| Variable | Description |
|----------|-------------|
| `--bg-gradient-start` | Color inicial del gradiente de fondo |
| `--bg-gradient-end` | Color final del gradiente |
| `--bg-gradient-angle` | Ángulo del gradiente (ej: 135deg) |

## Surfaces

| Variable | Description |
|----------|-------------|
| `--surface-primary` | Fondo de cards y contenedores |
| `--surface-secondary` | Fondo de elementos secundarios |
| `--surface-error` | Fondo de mensajes de error |

## Text

| Variable | Description |
|----------|-------------|
| `--text-primary` | Color de texto principal |
| `--text-secondary` | Texto secundario/muted |
| `--text-inverse` | Texto sobre fondos oscuros |
| `--text-link` | Color de enlaces |

## Typography

| Variable | Description |
|----------|-------------|
| `--font-family` | Familia tipográfica |
| `--font-size-xs` | 13px - legal, términos |
| `--font-size-sm` | 15px - texto secundario |
| `--font-size-base` | 16px - texto base |
| `--font-size-lg` | 17px - botones |
| `--font-size-xl` | 20px - subtítulos |
| `--font-size-2xl` | 28px - títulos |
| `--font-weight-normal` | 400 |
| `--font-weight-medium` | 500 |
| `--font-weight-semibold` | 600 |
| `--font-weight-bold` | 700 |
| `--line-height` | Altura de línea global |

## Spacing

| Variable | Description |
|----------|-------------|
| `--space-xs` | 8px |
| `--space-sm` | 12px |
| `--space-md` | 16px |
| `--space-lg` | 20px |
| `--space-xl` | 24px |
| `--space-2xl` | 32px |
| `--space-3xl` | 40px |

## Borders & Radius

| Variable | Description |
|----------|-------------|
| `--border-color` | Color de borde por defecto |
| `--border-color-hover` | Color de borde en hover |
| `--radius-sm` | 8px - botones, inputs |
| `--radius-md` | 12px - elementos medianos |
| `--radius-lg` | 16px - cards |
| `--radius-full` | Círculos perfectos |

## Shadows

| Variable | Description |
|----------|-------------|
| `--shadow-sm` | Sombra sutil |
| `--shadow-md` | Sombra de botones primary |
| `--shadow-lg` | Sombra de cards |
| `--shadow-button-hover` | Sombra en hover de botón primary |
| `--shadow-success` | Sombra de botones success (verde) |
| `--shadow-success-hover` | Sombra en hover de botón success |
| `--shadow-focus` | Sombra para estados de focus (inputs, selects) |

## Glass / Overlay (para elementos sobre gradiente)

| Variable | Description |
|----------|-------------|
| `--glass-bg` | Fondo semi-transparente (rgba 255,255,255,0.15) |
| `--glass-text` | Texto sobre gradiente (rgba 255,255,255,0.85) |
| `--glass-border` | Borde semi-transparente |
| `--glass-focus` | Box-shadow para focus en inputs sobre gradiente |
| `--error-bg-transparent` | Fondo de error semi-transparente |
| `--error-text-light` | Texto de error claro (para fondos oscuros) |
| `--success-bg-transparent` | Fondo de éxito semi-transparente (cards válidas) |

## Transitions

| Variable | Description |
|----------|-------------|
| `--transition-fast` | 0.15s - micro interacciones |
| `--transition-base` | 0.2s - interacciones normales |
| `--transition-slow` | 0.3s - animaciones |

## Animations

| Variable | Description |
|----------|-------------|
| `--animate-fade-in-1` | fadeInUp con delay 0.2s |
| `--animate-fade-in-2` | fadeInUp con delay 0.3s |
| `--animate-fade-in-3` | fadeInUp con delay 0.4s |
| `--animate-fade-in-4` | fadeInUp con delay 0.5s |
| `--animate-fade-in-5` | fadeInUp con delay 0.6s |

## Sizing

| Variable | Description |
|----------|-------------|
| `--icon-size-lg` | 64px - iconos grandes (wifi en welcome) |
| `--icon-size-md` | 48px - iconos medianos (spinner, wifi en questions) |
| `--control-size` | 18px - radio buttons, checkboxes |

## State Colors

| Variable | Description |
|----------|-------------|
| `--color-success-disabled` | Color de botón success deshabilitado |
| `--color-disabled` | Color de fondo para elementos deshabilitados |

## Layout

| Variable | Description |
|----------|-------------|
| `--content-max-width` | Ancho máximo del contenido |
| `--content-padding` | Padding interno de cards |

---

## Arquitectura de Layout

### Estructura HTML

```
.app-layout
├── .layout-content
│   └── .layout-view (.loading-state | .loaded-state)
│       ├── .view-icon (.brand-pulse cuando loading)
│       │   └── .brand-icon (img)
│       └── .view-content (.hidden | .visible)
│           └── [contenido de la vista]
└── .layout-footer (.hidden | .visible)
    └── [botones, selects, etc.]
```

### Clases de Estado

| Clase | Descripcion |
|-------|-------------|
| `.layout-view.loading-state` | Vista en estado de carga: icon centrado en viewport |
| `.layout-view.loaded-state` | Vista cargada: icon en posicion normal, contenido visible |
| `.view-icon` | Contenedor del brand icon |
| `.view-icon.brand-pulse` | Animacion de pulso durante carga |
| `.view-content.hidden` | Contenido oculto (opacity 0, translateY) |
| `.view-content.visible` | Contenido visible con animacion fadeInUp |
| `.layout-footer.hidden` | Footer oculto durante carga |
| `.layout-footer.visible` | Footer visible con animacion |

### Uso en Scala

```scala
Layout(
  isLoading = mySignal.map(_.isEmpty),  // Signal[Boolean]
  content = div(...),
  footer = div(...)
)
```

### Animaciones de Transicion

1. **Loading -> Loaded**: El brand icon hace `slideFromCenter` (0.8s)
2. **Content fadeIn**: Elementos hijos de `.view-content.visible` usan `--animate-fade-in-N`
3. **Footer fadeIn**: Usa `--animate-fade-in-4` cuando contenido es visible

### CSS Archivo Externo

El CSS esta en `client/assets/styles.css` y se carga via:
```html
<link rel="stylesheet" href="/assets/styles.css">
```

El servidor sirve el archivo desde `Main.scala`:
```scala
Method.GET / "assets" / "styles.css" ->
  zio.http.Handler.fromFile(java.io.File("client/assets/styles.css"))
```

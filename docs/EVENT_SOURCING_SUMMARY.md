# Event Sourcing - Portal Captivo Whitelabel

## Descripción General

Este documento describe el modelo de Event Sourcing para un portal captivo whitelabel que permite a usuarios conectarse a WiFi a cambio de responder preguntas de perfilado, ver publicidad y completar encuestas.

---

## 1. FLUJO DE EMAIL (Primera Pregunta)

### Evento Principal

| Actor | Comando | Agregado | Evento |
|-------|---------|----------|--------|
| Usuario | Responder 1ª Pregunta | Hotspot Externo Style | `EmailRegistrado` |

### Política

> "Mostrar primera pregunta de identificación al ingresar al Hotspot"

### Datos del Evento

- `email`: String
- `idioma`: String
- `dispositivo_id`: String (?)
- `sesion_id`: UUID

### Oportunidades

| ID | Descripción |
|----|-------------|
| OP-EMAIL-01 | Validación preliminar de email con regex antes de aceptar |
| OP-EMAIL-02 | Mandar link de validación **después** de validar que el usuario sea fiel (n cantidad de visitas) - no validar inmediatamente |

### Riesgos

| ID | Descripción |
|----|-------------|
| RI-EMAIL-01 | Email no existe / email inválido |

### Preguntas Abiertas

| ID | Pregunta |
|----|----------|
| QA-EMAIL-01 | ¿Cuántas visitas (n) definen a un usuario como "fiel" para enviar validación? |

---

## 2. FLUJO DE PERFILADO

### Evento Principal

| Actor | Comando | Agregado | Evento |
|-------|---------|----------|--------|
| Usuario | Responder Pregunta de Perfilado | Hotspot Externo Style | `PreguntaPerfiladoRespondida` |

### Política

> "Mostrar 1 o 2 (configurable) preguntas aleatorias del pool de preguntas de perfilado, si la primera de email fue respondida, al ingresar al Hotspot"

### Pool de Preguntas

- Número de teléfono
- Sexo
- Fecha de Nacimiento
- Nombre

### Datos del Evento

- `tipo_pregunta`: Enum (telefono, sexo, fecha_nacimiento, nombre)
- `respuesta`: String | Date
- `idioma`: String
- `sesion_id`: UUID

### Configuración

```
PerfiladoConfig:
  min_preguntas_por_sesion: Int (default: 1)
  max_preguntas_por_sesion: Int (default: 2)
```

### Preguntas Abiertas

| ID | Pregunta |
|----|----------|
| QA-PERF-01 | ¿El pool de preguntas de perfilado es fijo o configurable por cliente/hotspot? |

---

## 3. FLUJO DE UBICACIÓN

### Evento Principal

| Actor | Comando | Agregado | Evento |
|-------|---------|----------|--------|
| Usuario | Responder Pregunta | Hotspot Externo Style | `PreguntaUbicacionRespondida` |

### Política

> "Mostrar 1 o 2 (configurable) preguntas aleatorias del pool de preguntas: Estado-Ciudad-Municipio-Urbanización **(en ese orden jerárquico)**, una vez respondidas las preguntas de perfilado"

### Pool de Preguntas (Jerárquico)

1. Estado
2. Ciudad
3. Municipio
4. Urbanización

**Importante**: El orden es secuencial - no se puede preguntar Ciudad sin tener Estado.

### Datos del Evento

- `tipo_ubicacion`: Enum (estado, ciudad, municipio, urbanizacion)
- `respuesta_id`: UUID (referencia a catálogo)
- `idioma`: String
- `sesion_id`: UUID

### Oportunidades

| ID | Descripción |
|----|-------------|
| OP-UBIC-01 | Usar solo selección simple (Select/Dropdown) - no inputs libres |
| OP-UBIC-02 | Poblar DB con información geográfica de Venezuela |

### Riesgos

| ID | Descripción |
|----|-------------|
| RI-UBIC-01 | Respuestas semánticamente incoherentes (ej: Estado=Zulia, Ciudad=Caracas). Requiere validación de coherencia jerárquica |

### Preguntas Abiertas

| ID | Pregunta |
|----|----------|
| QA-UBIC-01 | ¿De dónde se obtiene el catálogo geográfico? ¿API externa o BD propia? |
| QA-UBIC-02 | ¿Qué hacer si el usuario selecciona combinaciones incoherentes? ¿Bloquear o alertar? |

---

## 4. FLUJO DE PUBLICIDAD

### Evento Principal

| Actor | Comando | Agregado | Evento |
|-------|---------|----------|--------|
| Usuario | Visualizar publicidad/propaganda | Hotspot Externo Style | `PublicidadVisualizada` |

### Políticas

| Política | Consecuencia |
|----------|--------------|
| Si se visualizó **PUBLICIDAD** (de anunciante) | → Mostrar preguntas de encuesta |
| Si se visualizó **PROPAGANDA** (de la empresa) | → Saltar directo a mensaje de agradecimiento |

### Tipos de Contenido

| Tipo | Origen | Flujo Posterior |
|------|--------|-----------------|
| **Publicidad** | Anunciantes externos | → Encuesta → Agradecimiento |
| **Propaganda** | Empresa dueña del hotspot | → Agradecimiento (sin encuesta) |

### Datos del Evento

- `publicidad_id`: UUID
- `tipo`: Enum (publicidad, propaganda)
- `anunciante_id`: UUID (nullable)
- `duracion_visualizada`: Int (segundos)
- `completado`: Boolean
- `sesion_id`: UUID

### Configuración

```
PublicidadConfig:
  limite_tiempo: 8 segundos
  mostrar_contador: Boolean (informar tiempo restante al usuario)
```

### Oportunidades

| ID | Descripción |
|----|-------------|
| OP-PUB-01 | Informar al usuario del tiempo de duración del anuncio (UX - contador visible) |
| OP-PUB-02 | Verificar tiempo en otros hotspots (benchmarking) |
| OP-PUB-03 | Incentivar interactividad del usuario mediante recompensas como participaciones en rifas |
| OP-PUB-04 | El anunciante podrá establecer objetivo temporal: semanal, bimestral, mensual |
| OP-PUB-05 | No mostrar el mismo video al mismo usuario en un límite temporal (día, semana, cantidad de horas) |

### Riesgos

| ID | Descripción |
|----|-------------|
| RI-PUB-01 | Publicidad agotada - no hay inventario de anuncios disponible |
| RI-PUB-02 | Cansar al usuario - saturación de publicidad |

### Preguntas Abiertas

| ID | Pregunta |
|----|----------|
| QA-PUB-01 | ¿Cómo se gestiona el inventario de publicidad? ¿Sistema propio o integración con ad network? |
| QA-PUB-02 | ¿Qué pasa si no hay publicidad disponible? ¿Se salta directo a encuesta o agradecimiento? |
| QA-PUB-03 | ¿Modelo de monetización? CPM, CPC, tiempo? |

---

## 5. FLUJO DE ENCUESTA

### Evento Principal

| Actor | Comando | Agregado | Evento |
|-------|---------|----------|--------|
| Usuario | Responder Encuesta | Hotspot Externo Style | `EncuestaRespondida` |

### Política

> "Mostrar un mensaje de agradecimiento después de rellenar una encuesta"

### Tipos de Preguntas Soportados

| Tipo | Componente UI | Validación |
|------|---------------|------------|
| Radio | Selección única | Requerido |
| Checkbox | Selección múltiple | Min/Max selecciones |
| Select | Dropdown | Requerido |
| Input | Texto libre | Limitable cantidad de caracteres |
| Rating | Estrellas/puntuación | Rango 1-5 o configurable |
| Numérico (Barra) | Slider | Min/Max valor |
| Date | Selector de fecha | Formato válido |

### Datos del Evento

- `pregunta_id`: UUID
- `tipo_pregunta`: Enum
- `respuesta`: String | Int | Date | List[String]
- `puntos_otorgados`: Int
- `idioma`: String
- `sesion_id`: UUID

### Sistema de Gamificación

```
GamificacionConfig:
  puntos_por_pregunta: Int
  mostrar_puntos_acumulados: Boolean
  sistema_canje: TBD
```

### Oportunidades

| ID | Descripción |
|----|-------------|
| OP-ENC-01 | Sistema de puntos canjeables por responder encuestas |
| OP-ENC-02 | Informar al usuario de cuántos puntos acumula con cada encuesta |

### Riesgos

| ID | Descripción |
|----|-------------|
| RI-ENC-01 | Cansar al usuario - demasiadas preguntas |
| RI-ENC-02 | Respuestas técnicamente incoherentes (fecha inválida, formato incorrecto) |
| RI-ENC-03 | Respuestas semánticamente incoherentes (contradicciones) |
| RI-ENC-04 | Preguntas agotadas - usuario ya respondió todo el pool |

### Preguntas Abiertas

| ID | Pregunta |
|----|----------|
| QA-ENC-01 | ¿Cuántas preguntas de encuesta por sesión? ¿Configurable como perfilado? |
| QA-ENC-02 | ¿Cómo funciona el sistema de canje de puntos? |
| QA-ENC-03 | ¿Comportamiento cuando se agotan preguntas? → "Se dejan de pedir respuestas" |

---

## 6. FLUJO UNIFI (Conexión/Desconexión)

### Evento 6.1: Desconexión Voluntaria

| Actor | Comando | Agregado | Evento |
|-------|---------|----------|--------|
| Usuario | Desconectar | Router UNIFI | `ConexionCulminada_Desconexion` |

### Evento 6.2: Timeout de Sesión

| Actor | Comando | Agregado | Evento |
|-------|---------|----------|--------|
| UNIFI (sistema) | Caducar conexión de usuario | Router UNIFI | `ConexionCulminada_Timeout` |

### Datos del Evento

- `usuario_id`: UUID
- `dispositivo_id`: String (MAC address?)
- `motivo`: Enum (desconexion_voluntaria, timeout, pagina_bloqueada, limite_descarga)
- `duracion_sesion`: Int (segundos)
- `paginas_visitadas`: List[String] (?)
- `bytes_descargados`: Long (?)

### Controles de Red (UNIFI)

| Control | Implementación |
|---------|----------------|
| **Páginas inadecuadas** | Blacklist de sitios web en UNIFI |
| **Tamaño de descargas** | Limitar mediante firewall |

### Estrategia de Reconexión

| Concepto | Comportamiento |
|----------|----------------|
| Al reconectarse | → Vuelve a ciclo normal (preguntas pendientes, publicidad, etc.) |
| Lapsos cortos | Máximo 5 minutos de sesión |

### Oportunidades

| ID | Descripción |
|----|-------------|
| OP-UNIFI-01 | Explotar la reconexión - oportunidad de mostrar más contenido/preguntas |
| OP-UNIFI-02 | Seguimiento por dispositivo para personalizar experiencia |

### Riesgos

| ID | Descripción |
|----|-------------|
| RI-UNIFI-01 | Reacción negativa al trabajo de reconexión - usuario molesto por tener que pasar por el flujo nuevamente |

### Preguntas Abiertas

| ID | Pregunta |
|----|----------|
| QA-UNIFI-01 | Limitación de tiempo: ¿Es fija? ¿O tiene seguimiento por dispositivo? |
| QA-UNIFI-02 | ¿Máximo 5 minutos es definitivo o se reevaluará en producción? |
| QA-UNIFI-03 | ¿Se guarda historial de páginas visitadas y descargas? ¿Implicaciones de privacidad? |

---

## RESUMEN CONSOLIDADO

### Diagrama de Flujo Completo

```
┌──────────────────────────────────────────────────────────────────────────┐
│                         PRIMERA VISITA                                    │
├──────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│   [QR/Acceso] → [EMAIL] → [PERFILADO 1-2] → [UBICACIÓN 1-2]             │
│                              ↓                                           │
│                    ┌─────────┴─────────┐                                │
│                    ↓                   ↓                                 │
│              PUBLICIDAD           PROPAGANDA                             │
│                    ↓                   ↓                                 │
│              [ENCUESTA]         [AGRADECIMIENTO]                        │
│                    ↓                                                     │
│              [AGRADECIMIENTO]                                           │
│                    ↓                                                     │
│              ══════════════════                                         │
│              ║  WiFi ACTIVO  ║                                          │
│              ══════════════════                                         │
│                                                                          │
└──────────────────────────────────────────────────────────────────────────┘
                                    ↓
┌──────────────────────────────────────────────────────────────────────────┐
│                         RECONEXIÓN (N+1)                                  │
├──────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│   [PREGUNTAS PENDIENTES] → [PUBLICIDAD] → [ENCUESTA?] → [WiFi]          │
│                                                                          │
│   * Solo preguntas que NO ha respondido en sesiones anteriores          │
│   * Máximo 5 minutos de sesión                                          │
│                                                                          │
└──────────────────────────────────────────────────────────────────────────┘
```

---

### Catálogo de Oportunidades

| ID | Flujo | Oportunidad | Prioridad |
|----|-------|-------------|-----------|
| OP-EMAIL-01 | Email | Validación preliminar con regex | Alta |
| OP-EMAIL-02 | Email | Link de validación después de n visitas (usuario fiel) | Media |
| OP-UBIC-01 | Ubicación | Usar solo Select (no inputs libres) | Alta |
| OP-UBIC-02 | Ubicación | Poblar DB con info geográfica Venezuela | Alta |
| OP-PUB-01 | Publicidad | Mostrar contador de tiempo al usuario | Media |
| OP-PUB-02 | Publicidad | Benchmarking con otros hotspots | Baja |
| OP-PUB-03 | Publicidad | Gamificación: rifas por interactividad | Media |
| OP-PUB-04 | Publicidad | Objetivos temporales para anunciantes | Media |
| OP-PUB-05 | Publicidad | No repetir video al mismo usuario en límite temporal | Alta |
| OP-ENC-01 | Encuesta | Sistema de puntos canjeables | Media |
| OP-ENC-02 | Encuesta | Mostrar puntos acumulados al usuario | Media |
| OP-UNIFI-01 | UNIFI | Explotar reconexión para más contenido | Media |
| OP-UNIFI-02 | UNIFI | Seguimiento por dispositivo | Alta |

---

### Catálogo de Riesgos

| ID | Flujo | Riesgo | Mitigación Propuesta |
|----|-------|--------|---------------------|
| RI-EMAIL-01 | Email | Email no existe/inválido | Validación regex + verificación posterior |
| RI-UBIC-01 | Ubicación | Respuestas semánticamente incoherentes | Validar coherencia jerárquica Estado→Ciudad→Municipio |
| RI-PUB-01 | Publicidad | Publicidad agotada | Fallback a propaganda o saltar a encuesta |
| RI-PUB-02 | Publicidad | Cansar al usuario | Límite de frecuencia configurable |
| RI-ENC-01 | Encuesta | Cansar al usuario | Límite de preguntas por sesión |
| RI-ENC-02 | Encuesta | Respuestas técnicamente incoherentes | Validación de formato por tipo |
| RI-ENC-03 | Encuesta | Respuestas semánticamente incoherentes | Detección de patrones contradictorios |
| RI-ENC-04 | Encuesta | Preguntas agotadas | Dejar de pedir respuestas, solo publicidad |
| RI-UNIFI-01 | UNIFI | Reacción negativa a reconexión | UX amigable, lapsos cortos, recordar progreso |

---

### Catálogo de Preguntas Abiertas

| ID | Flujo | Pregunta | Estado |
|----|-------|----------|--------|
| QA-EMAIL-01 | Email | ¿Cuántas visitas definen usuario "fiel" para validación? | Pendiente |
| QA-PERF-01 | Perfilado | ¿Pool de preguntas es fijo o configurable por hotspot? | Pendiente |
| QA-UBIC-01 | Ubicación | ¿Fuente del catálogo geográfico? ¿API o BD propia? | Pendiente |
| QA-UBIC-02 | Ubicación | ¿Qué hacer con combinaciones incoherentes? | Pendiente |
| QA-PUB-01 | Publicidad | ¿Sistema propio de ads o integración externa? | Pendiente |
| QA-PUB-02 | Publicidad | ¿Qué pasa sin publicidad disponible? | Pendiente |
| QA-PUB-03 | Publicidad | ¿Modelo de monetización? CPM, CPC, tiempo? | Pendiente |
| QA-ENC-01 | Encuesta | ¿Cuántas preguntas por sesión? ¿Configurable? | Pendiente |
| QA-ENC-02 | Encuesta | ¿Cómo funciona el canje de puntos? | Pendiente |
| QA-ENC-03 | Encuesta | ¿Comportamiento cuando se agotan preguntas? | Resuelto: "Se dejan de pedir" |
| QA-UNIFI-01 | UNIFI | ¿Tiempo fijo o por dispositivo? | Pendiente |
| QA-UNIFI-02 | UNIFI | ¿5 min definitivo o reevaluar en producción? | Reevaluar |
| QA-UNIFI-03 | UNIFI | ¿Guardar historial de navegación? ¿Privacidad? | Pendiente |

---

### Eventos del Sistema

```
Agregado: HotspotExternoStyle
├── EmailRegistrado
├── PreguntaPerfiladoRespondida
├── PreguntaUbicacionRespondida
├── PublicidadVisualizada
└── EncuestaRespondida

Agregado: RouterUNIFI
├── ConexionCulminada_Desconexion
└── ConexionCulminada_Timeout
```

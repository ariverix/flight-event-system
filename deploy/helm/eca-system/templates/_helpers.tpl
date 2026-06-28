{{/*
_helpers.tpl — переиспользуемые шаблоны (named templates) чарта eca-system.
Все функции возвращают строку без trailing-newline ({{- define ... -}}).
*/}}

{{/*
Expand the name of the chart.
*/}}
{{- define "eca-system.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Create a default fully qualified app name (63 chars max — DNS label limit).
If release name already contains chart name → use release name only (no duplication).
*/}}
{{- define "eca-system.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{/*
Chart label: name + version (used in helm.sh/chart label).
*/}}
{{- define "eca-system.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Common labels — проставляются на все K8s-ресурсы чарта.
*/}}
{{- define "eca-system.labels" -}}
helm.sh/chart: {{ include "eca-system.chart" . }}
{{ include "eca-system.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{/*
Selector labels — используются в Deployment.spec.selector и Service.spec.selector.
Не менять после первого деплоя: смена требует пересоздания Deployment.
*/}}
{{- define "eca-system.selectorLabels" -}}
app.kubernetes.io/name: {{ include "eca-system.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/*
Selector labels для компонента backend.
*/}}
{{- define "eca-system.backend.selectorLabels" -}}
app.kubernetes.io/name: {{ include "eca-system.name" . }}-backend
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/component: backend
{{- end }}

{{/*
Selector labels для компонента frontend.
*/}}
{{- define "eca-system.frontend.selectorLabels" -}}
app.kubernetes.io/name: {{ include "eca-system.name" . }}-frontend
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/component: frontend
{{- end }}

{{/*
Selector labels для компонента postgres.
*/}}
{{- define "eca-system.postgres.selectorLabels" -}}
app.kubernetes.io/name: {{ include "eca-system.name" . }}-postgres
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/component: postgres
{{- end }}

{{/*
Имя ServiceAccount.
Если serviceAccount.create=true и name пусто → автогенерация.
*/}}
{{- define "eca-system.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (printf "%s-sa" (include "eca-system.fullname" .)) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}

{{/*
Имя K8s Secret с DB credentials.
Если dbSecretName задан явно — используем его (внешний Secret Manager).
Иначе — имя формируется чартом: {{ fullname }}-db.
*/}}
{{- define "eca-system.dbSecretName" -}}
{{- default (printf "%s-db" (include "eca-system.fullname" .)) .Values.backend.dbSecretName }}
{{- end }}

{{/*
Имя K8s Secret с JWT-ключом.
*/}}
{{- define "eca-system.jwtSecretName" -}}
{{- default (printf "%s-jwt" (include "eca-system.fullname" .)) .Values.backend.jwtSecretName }}
{{- end }}

{{/*
DNS-имя Service для PostgreSQL (внутри кластера).
*/}}
{{- define "eca-system.postgresServiceName" -}}
{{- printf "%s-postgres" (include "eca-system.fullname" .) }}
{{- end }}

{{/*
DNS-имя Service для backend.
*/}}
{{- define "eca-system.backendServiceName" -}}
{{- printf "%s-backend" (include "eca-system.fullname" .) }}
{{- end }}

{{/*
DNS-имя Service для frontend.
*/}}
{{- define "eca-system.frontendServiceName" -}}
{{- printf "%s-frontend" (include "eca-system.fullname" .) }}
{{- end }}

{{/*
Имя ConfigMap с nginx-конфигурацией и env.js для frontend.
*/}}
{{- define "eca-system.nginxConfigMapName" -}}
{{- printf "%s-nginx" (include "eca-system.fullname" .) }}
{{- end }}

{{/*
Имя ConfigMap с общей конфигурацией приложения (non-secret).
*/}}
{{- define "eca-system.appConfigMapName" -}}
{{- printf "%s-config" (include "eca-system.fullname" .) }}
{{- end }}

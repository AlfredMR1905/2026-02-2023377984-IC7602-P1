# Proyecto 1 - Servicio DNS

Este proyecto implementa un servicio DNS en Rust para IC7602. El interceptor recibe consultas por UDP, revisa si el dominio está configurado en Supabase y responde con una dirección IPv4 según la política del registro. Cuando el dominio no existe, envía el paquete a una API en Java para que consulte un servidor DNS externo. También incluye una interfaz en React para administrar los registros y un chart de Helm para desplegar los componentes en Kubernetes.

El proyecto fue desarrollado individualmente por Alfredo Enrique Mercado Rios, carné 2023377984. Por indicación del profesor, los health checks y la política `round-trip` quedaron fuera del alcance de esta entrega.

La explicación del funcionamiento, las pruebas y las limitaciones se encuentran en [DOCUMENTACION.md](DOCUMENTACION.md).

## Requisitos

- Docker Desktop con Kubernetes habilitado, Docker, `kubectl` y Helm.
- Bash para ejecutar el instalador. En Windows se puede utilizar Git Bash. La implementación se probó con el nodo `desktop-control-plane` de Docker Desktop.
- Conexión a Internet para descargar las imágenes, consultar Supabase y resolver dominios externos.

## Instalación automatizada

Desde la raíz del repositorio, ejecutar:

```bash
bash install.sh
```

El script crea el Secret con la URL y la publishable key del Supabase de demostración, construye e importa las tres imágenes al nodo de Docker Desktop, instala Helm y espera a que los pods estén listos. Después prueba `ejemplo.test` con un cliente dentro de Kubernetes y con otro cliente Linux fuera del clúster. Por último abre la UI mediante `port-forward` y muestra su dirección, normalmente `http://localhost:8080`. Hay que mantener esa terminal abierta mientras se usa la interfaz. Para detener el acceso local a la UI se presiona `Ctrl+C`.

El proyecto de Supabase ya tiene los registros de ejemplo. Si se quiere recrearlos en otro proyecto, se ejecutan [`dns_api/schema.sql`](dns_api/schema.sql) y luego [`dns_api/sample_data.sql`](dns_api/sample_data.sql). Los comandos para hacer cada paso manualmente están en [DOCUMENTACION.md](DOCUMENTACION.md).

## Uso

En la dirección que muestra el script se pueden crear, editar y borrar dominios con políticas `single`, `multi`, `weight` y `geo`. También se administran las redes IP-país. Los cambios pasan por la API Java y se guardan en Supabase.

El script comprueba la consulta local `ejemplo.test`, que con los datos de ejemplo devuelve `10.0.0.25`. Para revisar las demás políticas se pueden consultar `multi.test`, `weight.test` y `geo.test` con los comandos de la documentación. `example.com` permite probar el reenvío al DNS externo.

## Arquitectura

| Componente | Responsabilidad |
| --- | --- |
| Interceptor Rust | Recibe paquetes UDP/53, lee la consulta, selecciona la IPv4 local o solicita el reenvío. |
| API Java | Consulta y modifica los datos de Supabase; reenvía paquetes al DNS externo cuando Rust se lo pide. |
| UI React | Presenta los formularios para administrar dominios y redes IP-país. |
| Supabase | Almacena los dominios, sus direcciones y las redes IP-país. |
| Helm y Kubernetes | Ejecutan los contenedores y conectan sus servicios. |

Rust se comunica con la API mediante HTTPS dentro del clúster. La API corre por HTTP en su pod y nginx recibe HTTPS en el mismo pod. El certificado de demostración se incluye en el chart. La UI se comunica con Java a través de su propio nginx.

## Estado de las funcionalidades

| Aspecto | Estado | Detalle |
| --- | --- | --- |
| `single` | Funciona | Devuelve la única IPv4 del dominio. |
| `multi` | Funciona | Recorre las direcciones por turnos. Los turnos están en memoria de Rust. |
| `weight` | Funciona | Repite cada dirección según su peso dentro de un ciclo. |
| `geo` | Funciona | Usa el país asociado a la IP de origen; si no hay coincidencia, selecciona una dirección aleatoria. |
| API Java y Supabase | Funciona | Ofrece consultas, CRUD y reenvío al DNS externo. |
| UI React | Funciona | Administra dominios y redes IP-país por medio de la API. |
| Health checks y `round-trip` | Fuera de alcance | Se retiraron del proyecto. |
| `nslookup` nativo de Windows en esta instalación | No comprobado | Windows ocupa UDP/53 y el `LoadBalancer` muestra `EXTERNAL-IP <pending>`. Sí responde desde Kubernetes y desde un cliente Docker externo al clúster. |

## Limitaciones

- La respuesta local implementada corresponde a consultas A/IN. Las demás consultas se envían al DNS externo.
- Los turnos de `multi` y `weight` se reinician cuando se reinicia Rust y no se comparten entre réplicas.
- En el equipo utilizado, Internet Connection Sharing de Windows ocupa UDP/53 e impide que Docker Desktop publique el `LoadBalancer` en ese puerto. Esto afecta la prueba con `nslookup` nativo de Windows, pero no las pruebas anteriores.

## Documentos incluidos

- [Documentación de instalación, funcionamiento y pruebas](DOCUMENTACION.md)
- [Reportes de avance](Reports/)

## Uso de herramientas de apoyo

Durante el desarrollo se utilizó Codex para explicar conceptos de DNS y Rust, revisar el código, preparar partes de la implementación como el DNS UI y diagnosticar problemas de integración. Las indicaciones utilizadas y las dificultades encontradas se detallan en la documentación del proyecto.

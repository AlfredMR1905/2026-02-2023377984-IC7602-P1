# Documentación del Proyecto 1 - Servicio DNS

**Curso:** IC7602 Redes  
**Autor:** Alfredo Enrique Mercado Rios, 2023377984  
**Entorno comprobado:** Windows, Docker Desktop con Kubernetes local y Supabase. Los comandos se presentan en Bash para poder reutilizarlos en otros sistemas.

## Funcionamiento

El proyecto tiene cuatro partes: el interceptor DNS en Rust, la API en Java, la interfaz en React y los datos en Supabase. Cuando un cliente consulta un nombre, no habla directamente con la API ni con Supabase. Primero envía un paquete DNS por UDP al interceptor. Rust lee ese paquete, decide si puede responder con un registro local y, si no puede, le pide a Java que lo reenvíe a un DNS externo.

```text
Cliente DNS ── UDP/53 ──► Interceptor Rust
                             │ HTTPS:8443
                             ▼
                      proxy en pod DNS API ── HTTP localhost ──► API Java
                                                                  │      │
                                                           Supabase   DNS externo

Navegador ──► UI React/nginx ── HTTP interno ──► API Java
```

Rust recibe el datagrama, lee la cabecera y la pregunta DNS, y pregunta a la API si existe el dominio. Si encuentra un registro local y la consulta es de tipo A y clase IN, selecciona una IPv4 y construye la respuesta. Para un dominio que no está configurado, o una consulta que no puede resolver localmente, envía el paquete completo en Base64 a `POST /api/dns_resolver`. Java lo decodifica, consulta por UDP al DNS externo y devuelve la respuesta en Base64. Rust se la envía al cliente. Cada datagrama se atiende en un hilo independiente.

Java administra registros y redes IP-país en Supabase. La UI permite crearlos, editarlos y borrarlos mediante Java. La comunicación Rust-Java utiliza HTTPS: nginx recibe la petición en el mismo pod de Java y se la entrega por `localhost:8080`. Rust verifica el certificado de demostración incluido en el chart. La UI usa la conexión HTTP interna con Java.

Esta separación tiene una razón práctica. El cliente solo necesita conocer el DNS; Rust se encarga del protocolo DNS y Java concentra el acceso a los datos y el reenvío. La interfaz tampoco necesita conocer la clave de Supabase.

Una consulta DNS no es una petición HTTP. Llega a Rust como una secuencia de bytes con una cabecera y una pregunta. En la cabecera se encuentra, entre otras cosas, el identificador que permite relacionar la respuesta con la consulta. La pregunta contiene el nombre, el tipo solicitado (`A` para IPv4 en las respuestas locales) y la clase (`IN`). Rust conserva la pregunta y el identificador al construir la respuesta. El nombre puede estar dividido en etiquetas, como `ejemplo` y `test`, y también puede incluir punteros de compresión que reutilizan una parte escrita antes en el paquete. Por eso el código no puede tratar el paquete simplemente como texto.

Cuando el dominio sí está configurado, la selección depende de la política. `single` devuelve su única IP. `multi` recorre las IP por turnos. `weight` también lleva un turno, pero cada IP ocupa tantas posiciones como indique su peso. `geo` busca primero el país de la IP del cliente y, si no hay una coincidencia, elige una de las IP al azar. Los turnos de `multi` y `weight` viven en la memoria del proceso Rust, no en Supabase.

## Requisitos

- Docker Desktop con Kubernetes habilitado, `docker`, `kubectl`, `helm` y Bash. En Windows se puede utilizar Git Bash o WSL.
- Acceso al proyecto de demostración de Supabase. Su URL y publishable key se incluyen en esta guía. Si se quiere usar otro proyecto, se necesita acceso a su SQL Editor.
- Acceso a Internet para descargar imágenes base, consultar Supabase y usar el DNS externo.

Los comandos siguientes se ejecutan en la raíz del repositorio. El despliegue se probó con el namespace `default` y el nodo `desktop-control-plane` de Docker Desktop. Comprobar el nombre del nodo con `kubectl get nodes` y sustituirlo en los comandos de importación si es diferente. En Linux o macOS se necesita un clúster Kubernetes y acceso a Docker; la importación con `ctr` corresponde específicamente al nodo `kind` usado por Docker Desktop en esta instalación.

## Instalación automatizada

Con Docker Desktop y Kubernetes en ejecución, abrir Git Bash en la raíz del repositorio y ejecutar:

```bash
bash install.sh
```

El script crea el Secret de Supabase, construye e importa las imágenes, instala el chart y espera a que los tres pods estén listos. Luego ejecuta dos consultas DNS de prueba y muestra la dirección de la UI, normalmente `http://localhost:8080`. Si ese puerto está ocupado, utiliza el siguiente que esté libre. Se mantiene la terminal abierta mientras se utiliza la interfaz; `Ctrl+C` detiene el `port-forward`. Las secciones siguientes muestran los mismos pasos por separado para entenderlos o repetir alguno si falla.

## Preparar Supabase manualmente

El proyecto de demostración ya tiene el esquema y los registros de ejemplo. Para utilizarlo, crear el Secret que leerá la API Java:

```bash
supabase_url='https://wvopbidgfcairisohpdx.supabase.co'
supabase_key='sb_publishable_ZGfvGbvJcS5b4PIsv1i3_A_Q4eSiIR3'
kubectl create secret generic dns-supabase --from-literal="url=$supabase_url" --from-literal="key=$supabase_key" --dry-run=client -o yaml | kubectl apply -f -
```

La URL y la clave anteriores permiten probar directamente la base utilizada en la entrega. Si se utiliza otro proyecto, primero se ejecuta `dns_api/schema.sql` y luego `dns_api/sample_data.sql` en su SQL Editor, y se sustituyen los dos valores del comando.

El Secret `dns-supabase` debe existir antes de instalar Helm. El Secret TLS `dns-api-demo-tls` es diferente: Helm lo crea automáticamente con `helm/dns-platform/certs/tls.crt` y `tls.key`. Son archivos de demostración incluidos en el repositorio para evitar un paso manual adicional.

## Construir y desplegar manualmente

```bash
docker build -t dns-api:local ./dns_api
docker build -t dns-interceptor:local ./dns_interceptor
docker build -t dns-ui:local ./dns_ui
```

En este Docker Desktop, Kubernetes utiliza un nodo `kind` con almacén de imágenes separado. Importar las imágenes:

```bash
docker save dns-api:local | docker exec -i desktop-control-plane ctr -n k8s.io images import -
docker save dns-interceptor:local | docker exec -i desktop-control-plane ctr -n k8s.io images import -
docker save dns-ui:local | docker exec -i desktop-control-plane ctr -n k8s.io images import -
```

Instalar o actualizar el proyecto:

```bash
helm upgrade --install dns-platform ./helm/dns-platform
kubectl get pods
kubectl get services
```

Se esperan un pod de Rust listo, uno de Java con **dos** contenedores listos (Java y proxy HTTPS), y uno de UI listo. Si se reconstruyen imágenes conservando el tag `local`, volver a importarlas y ejecutar `kubectl rollout restart deployment/dns-api deployment/dns-interceptor deployment/dns-ui` para que Kubernetes tome las nuevas versiones.

Los puertos, nombres de imágenes y DNS remoto (`8.8.8.8:53` de ejemplo) se configuran en `helm/dns-platform/values.yaml`. Rust escucha UDP/53 dentro del clúster.

## Interfaz y API

Mantener abierta una terminal con:

```bash
kubectl port-forward svc/dns-ui 8080:80
```

Abrir `http://localhost:8080`. Desde otra terminal, comprobar la API mediante el proxy de la UI:

```bash
curl -sS 'http://localhost:8080/api/exists?domain=ejemplo.test'
curl -sS 'http://localhost:8080/api/domains'
curl -sS 'http://localhost:8080/api/ip-networks'
```

Prueba de CRUD: crear en la UI `demo.test` con política `single` e IP `10.0.0.50`, consultar el dominio con el comando DNS de la sección siguiente, editar la IP y consultar de nuevo, y finalmente borrar el registro. La lista de la UI debe reflejar cada operación; los datos permanecen en Supabase.

## Pruebas DNS

Primero se puede consultar desde el clúster. Este comando crea un cliente BusyBox temporal y lo elimina al terminar:

```bash
kubectl run dns-demo --rm -i --restart=Never --image=busybox:1.36 -- nslookup -type=a ejemplo.test dns-interceptor
```

También se comprobó un cliente **fuera de Kubernetes**. Es un contenedor Linux temporal en la red Docker `kind` que consulta el `NodePort` del servicio. El comando se lanza desde Bash:

```bash
node_ip=$(kubectl get node desktop-control-plane -o jsonpath='{.status.addresses[?(@.type=="InternalIP")].address}')
node_port=$(kubectl get svc dns-interceptor -o jsonpath='{.spec.ports[0].nodePort}')
docker run --rm --network kind busybox:1.36 nslookup -type=a "-port=$node_port" ejemplo.test "$node_ip"
```

Con los datos de ejemplo, este cliente recibió `10.0.0.25` para `ejemplo.test` y también resolvió `example.com`. Para probar este último caso, repetir la orden sustituyendo `ejemplo.test` por `example.com`. El nombre del nodo y de la red Docker corresponden a la instalación donde se hicieron las pruebas.

En esa misma instalación, el servicio DNS de tipo `LoadBalancer` muestra `EXTERNAL-IP <pending>`: Internet Connection Sharing de Windows ocupa UDP/53 e impide que Docker Desktop publique ese puerto. Se intentó detener el servicio, pero Windows volvió a iniciarlo. Por tanto, estas pruebas demuestran que DNS funciona dentro de Kubernetes y desde un cliente Linux externo al clúster, pero **no** demuestran una consulta con `nslookup` nativo de Windows.

Con los datos de ejemplo, `ejemplo.test` responde `10.0.0.25`. Repetir el comando sustituyendo el dominio:

| Dominio | Prueba | Resultado esperado |
| --- | --- | --- |
| `multi.test` | Consultar dos veces. | Alterna `10.0.0.11` y `10.0.0.12`; el primer turno depende de consultas previas. |
| `weight.test` | Consultar cuatro veces. | Tres respuestas `10.0.0.21` y una `10.0.0.22` por ciclo. |
| `geo.test` | Consultar y revisar la IP de origen en los logs de Rust. | Si la IP coincide con una red marcada como `CR`, responde `10.0.0.31`; si no coincide con ninguna red, elige entre `10.0.0.31` y `10.0.0.32`. |
| `example.com` | Consultar un dominio ausente en Supabase. | IPv4 públicas devueltas por el DNS externo; pueden variar. |

Para entender `geo`, hay que mirar primero la IP que Rust ve como origen: `kubectl logs deployment/dns-interceptor`. Java busca esa IP en las redes IP-país de Supabase y devuelve el país de la coincidencia más específica. Los datos de ejemplo incluyen `10.0.0.0/8` como `CR`; por ello, una IP interna `10.x.x.x` selecciona la dirección costarricense. Si se quiere comprobar el caso aleatorio, se debe probar desde un origen que no coincida con las redes cargadas o modificar temporalmente esas redes desde la interfaz.

Se comprobaron manualmente `single`, `multi`, `weight`, `geo` y la resolución externa con `nslookup` desde el clúster. También se comprobó la consulta externa al clúster desde el contenedor Linux. Pasaron `cargo check` y `helm lint helm/dns-platform`. No se incluye una suite de pruebas automatizadas.

## Estado de implementación

| Componente | Estado | Límite conocido |
| --- | --- | --- |
| Interceptor: `single` | Funciona | Responde A/IN. |
| Interceptor: `multi` | Funciona | Turnos almacenados en memoria de la instancia. |
| Interceptor: `weight` | Funciona | Distribución por pesos enteros. |
| Interceptor: `geo` | Funciona | Sin país coincidente elige una IP aleatoria. |
| Interceptor: `round-trip` | No implementado | Dependía de health checks retirados del alcance por indicación del docente. |
| API Java | Funciona | Requiere Supabase y acceso al DNS externo. |
| UI React | Funciona | Requiere la API y Supabase. |
| Health Checker | Fuera de alcance | Retirado |
| HTTPS Rust → API | Funciona | Certificado local de demostración incluido en el chart. |
| IP externa del DNS en Docker Desktop | No disponible en UDP/53 | Windows ya ocupa ese puerto; la prueba reproducible usa clientes dentro y fuera de Kubernetes. |

## Uso de IA y dificultades

Durante el desarrollo utilicé Codex para trabajar en partes del código, explicar conceptos que todavía no dominaba, revisar problemas de integración y preparar pruebas. No se trató solo de pedir un resultado: varias veces volví sobre la misma parte hasta entender qué hacía cada línea, especialmente con los paquetes DNS. Por ejemplo, pedí que se avanzara «sin sobreingenierizar» y que se explicara el código junto con la teoría de redes para poder entenderlo y defenderlo en la revisión.

En DNS UI utilicé IA generativa para preparar la estructura de los formularios, las listas de dominios y redes, y los estilos. La indicación de mantener el proyecto sencillo también aplicó a esta parte: la interfaz debía mostrar únicamente las políticas implementadas (`single`, `multi`, `weight` y `geo`) y permitir administrar dominios y redes IP-país. Después pedí que se hicieran las pruebas de la UI, se reiniciaran los componentes y se levantara todo de nuevo para comprobar que funcionara junto con Java y Supabase. Revisé que los campos coincidieran con lo que recibe la API: dominio, política, TTL, direcciones, peso y país. La integración quedó en `dns_ui/src/api.js`, donde las operaciones de crear, editar y borrar hacen peticiones HTTP a Java. Probé el CRUD desde la interfaz y comprobé que los cambios aparecieran en Supabase y afectaran las respuestas DNS.

Los problemas de esta parte fueron conectar la UI con los endpoints reales, manejar los errores que devuelve Java y asegurar que Kubernetes usara la imagen recién construida. No bastaba con cambiar React y ejecutar `docker build`: había que importar la imagen al nodo y reiniciar el deployment.

La primera dificultad fue entender los punteros de compresión DNS. Al comienzo los confundía con referencias a nombres que el programa ya había leído, cuando realmente son desplazamientos a bytes anteriores del mismo paquete. Para aclararlo seguí el cursor, el destino del puntero y la posición donde continúa la pregunta. Otra dificultad fue que construir una imagen nueva en Docker no actualizaba automáticamente la imagen que ejecutaba el nodo Kubernetes; por eso se agregó el paso de importarla con `ctr`. También hubo que conectar Java con la API REST de Supabase, comprobar el certificado HTTPS de Rust a Java y diagnosticar por qué Docker Desktop no podía publicar UDP/53 en Windows. Ese último problema permanece como limitación del entorno de demostración.

## Recomendaciones

1. Ejecutar `schema.sql` antes de `sample_data.sql`, ya que los ejemplos necesitan las tablas creadas.
2. Crear `dns-supabase` antes de instalar Helm; de lo contrario, el pod de Java no tendrá la configuración necesaria.
3. Utilizar la Project URL y la publishable key de Supabase. Esta API no abre una conexión PostgreSQL directa.
4. Revisar `kubectl get pods` y esperar a que estén listos antes de atribuir un fallo a DNS o a la interfaz.
5. Probar primero `ejemplo.test`, porque separa la resolución local de la consulta al DNS externo.
6. Consultar después `example.com`, que permite comprobar el recorrido Rust-Java-DNS externo.
7. Repetir las consultas de `multi` y `weight`: una sola respuesta no muestra cómo cambian los turnos.
8. Revisar la IP de origen en los logs antes de evaluar `geo`; Kubernetes puede mostrar a Rust una IP diferente de la del equipo físico.
9. Volver a importar las imágenes al nodo si se reconstruyen con el mismo tag `local`; un `docker build` por sí solo no actualiza los pods.
10. Si una consulta no responde, revisar primero los logs de Rust y después los de Java y `https-proxy`, siguiendo el mismo recorrido que hace el paquete.

## Conclusiones

1. El interceptor recibe datagramas por UDP/53 en Kubernetes y responde a consultas DNS reales hechas con `nslookup`.
2. Leer el paquete por campos es necesario: la cabecera, el nombre, el tipo y la clase determinan qué respuesta corresponde construir.
3. Los punteros de compresión evitan repetir partes del nombre dentro del paquete, pero obligan a distinguir entre la posición donde continúa la pregunta y la posición desde la que se completa el nombre.
4. La respuesta local conserva el identificador y la pregunta originales para que el cliente pueda relacionarla con su consulta.
5. Separar Rust de Java permitió dejar el protocolo DNS en el interceptor y las operaciones de Supabase en la API.
6. `single` y `multi` pueden usar los mismos registros de direcciones, aunque uno siempre elige una IP y el otro avanza por turnos.
7. `weight` no devuelve todas las IP juntas: aumenta la frecuencia con la que aparece cada una según su peso.
8. `geo` depende de la IP que llega efectivamente a Rust. En Kubernetes esa IP puede ser distinta de la IP física del cliente.
9. Para un dominio no configurado, Java reenvía el paquete por UDP al DNS externo y la respuesta vuelve a Rust codificada en Base64 a través de HTTPS.
10. Helm permite levantar los componentes con una configuración repetible. La publicación de UDP/53 hacia Windows sigue limitada por el puerto ocupado en el equipo de prueba, aunque las consultas dentro y fuera de Kubernetes sí funcionaron.



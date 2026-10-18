package com.example.myapplication

import android.app.TimePickerDialog
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.room.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.util.*

@Entity data class Usuario(@PrimaryKey val user: String, val pass: String)

@Entity data class Alarma(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val usuarioOwner: String,
    val hora: String,
    val dias: String,
    val ipDestino: String,
    val activa: Boolean = true
)

@Dao
interface AppDao {
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun registrarUser(u: Usuario)
    @Query("SELECT * FROM Usuario WHERE user = :u LIMIT 1") suspend fun buscarUsuario(u: String): Usuario?
    @Query("SELECT * FROM Usuario WHERE user = :u AND pass = :p LIMIT 1") suspend fun login(u: String, p: String): Usuario?

    @Insert suspend fun crearAlarma(a: Alarma)
    @Query("SELECT * FROM Alarma WHERE usuarioOwner = :u ORDER BY id DESC") suspend fun obtenerAlarmas(u: String): List<Alarma>
    @Query("DELETE FROM Alarma WHERE id = :id") suspend fun eliminarAlarma(id: Int)
}

@Database(entities = [Usuario::class, Alarma::class], version = 7, exportSchema = false)
abstract class AppDB : RoomDatabase() {
    abstract fun dao(): AppDao
    companion object {
        fun get(ctx: Context) = Room.databaseBuilder(ctx, AppDB::class.java, "db")
            .fallbackToDestructiveMigration()
            .build()
    }
}

fun hashPassword(password: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(password.toByteArray())
    return bytes.joinToString("") { "%02x".format(it) }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        enableEdgeToEdge()
        val dao = AppDB.get(this).dao()
        setContent {

            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val nav = rememberNavController()
                    NavHost(navController = nav, startDestination = "login") {
                        composable("login") { LoginScreen(nav, dao) }
                        composable("registro") { RegistroScreen(nav, dao) }
                        composable(
                            route = "panel/{usuarioLogueado}",
                            arguments = listOf(navArgument("usuarioLogueado") { type = NavType.StringType })
                        ) { backStackEntry ->
                            val userLogged = backStackEntry.arguments?.getString("usuarioLogueado") ?: ""
                            PanelScreen(dao, userLogged, nav)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LoginScreen(nav: NavHostController, dao: AppDao) {
    var u by remember { mutableStateOf("") }
    var p by remember { mutableStateOf("") }
    var passVisible by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier.fillMaxSize().systemBarsPadding().padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Despertador IoT",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 20.dp)
                )

                OutlinedTextField(
                    value = u,
                    onValueChange = { u = it },
                    label = { Text("Usuario") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                )

                OutlinedTextField(
                    value = p,
                    onValueChange = { p = it },
                    label = { Text("Contraseña") },
                    singleLine = true,
                    visualTransformation = if (passVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { passVisible = !passVisible }) {
                            Icon(
                                imageVector = if (passVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                                contentDescription = "Mostrar/Ocultar"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp)
                )

                Button(
                    onClick = {
                        scope.launch {
                            val userValido = dao.login(u.trim(), hashPassword(p))
                            if (userValido != null) {
                                nav.navigate("panel/${userValido.user}")
                            } else {
                                Toast.makeText(ctx, "Datos incorrectos", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Iniciar Sesión", fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.height(8.dp))

                TextButton(
                    onClick = { nav.navigate("registro") },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Crear Cuenta / Registrarse", color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
fun RegistroScreen(nav: NavHostController, dao: AppDao) {
    var u by remember { mutableStateOf("") }
    var p by remember { mutableStateOf("") }
    var passVisible by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier.fillMaxSize().systemBarsPadding().padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Registro de Usuario",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 20.dp)
                )

                OutlinedTextField(
                    value = u,
                    onValueChange = { u = it },
                    label = { Text("Nuevo Usuario") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                )

                OutlinedTextField(
                    value = p,
                    onValueChange = { p = it },
                    label = { Text("Nueva Contraseña") },
                    singleLine = true,
                    visualTransformation = if (passVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { passVisible = !passVisible }) {
                            Icon(
                                imageVector = if (passVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                                contentDescription = "Mostrar/Ocultar"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp)
                )

                Button(
                    onClick = {
                        val usuarioLimpio = u.trim()
                        if (usuarioLimpio.isNotBlank() && p.isNotBlank()) {
                            scope.launch {
                                val existe = dao.buscarUsuario(usuarioLimpio)
                                if (existe != null) {
                                    Toast.makeText(ctx, "El usuario ya existe", Toast.LENGTH_SHORT).show()
                                } else {
                                    dao.registrarUser(Usuario(usuarioLimpio, hashPassword(p)))
                                    Toast.makeText(ctx, "Registrado con éxito", Toast.LENGTH_SHORT).show()
                                    nav.popBackStack()
                                }
                            }
                        } else {
                            Toast.makeText(ctx, "Completa los campos", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Guardar Usuario", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PanelScreen(dao: AppDao, usuarioActual: String, nav: NavHostController) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("iot_prefs", Context.MODE_PRIVATE) }

    var ip by remember { mutableStateOf(prefs.getString("last_ip", "") ?: "") }
    var estadoConexion by remember { mutableStateOf("Sin probar") }
    var probandoConexion by remember { mutableStateOf(false) }

    var horaSeleccionada by remember { mutableStateOf("07:00") }

    val diasMap = listOf("L" to "Lun", "M" to "Mar", "X" to "Mie", "J" to "Jue", "V" to "Vie", "S" to "Sab", "D" to "Dom")
    var diasSeleccionados by remember { mutableStateOf(setOf<String>()) }
    var listaAlarmas by remember { mutableStateOf(listOf<Alarma>()) }

    val scope = rememberCoroutineScope()
    val esConexionValida = estadoConexion == "Conectado"

    LaunchedEffect(usuarioActual) {
        listaAlarmas = dao.obtenerAlarmas(usuarioActual)
    }

    val timePickerDialog = TimePickerDialog(
        ctx,
        { _, hourOfDay, minute ->
            horaSeleccionada = String.format(Locale.getDefault(), "%02d:%02d", hourOfDay, minute)
        },
        7, 0, true
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Panel IoT ($usuarioActual)", color = MaterialTheme.colorScheme.onSurface) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                actions = {
                    TextButton(onClick = { nav.navigate("login") { popUpTo(0) } }) {
                        Text("Salir", color = Color(0xFFD32F2F), fontWeight = FontWeight.Bold)
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Conexión Receptor TCP", fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))

                        OutlinedTextField(
                            value = ip,
                            onValueChange = {
                                ip = it
                                estadoConexion = "Sin probar"
                                prefs.edit().putString("last_ip", it).apply()
                            },
                            label = { Text("IP Dispositivo") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = {
                                    if (ip.isBlank()) {
                                        Toast.makeText(ctx, "Ingresa una IP", Toast.LENGTH_SHORT).show()
                                    } else {
                                        probandoConexion = true
                                        estadoConexion = "Probando..."
                                        scope.launch(Dispatchers.IO) {
                                            val exito = probarSocket(ip, 8080)
                                            withContext(Dispatchers.Main) {
                                                probandoConexion = false
                                                estadoConexion = if (exito) "Conectado" else "Sin respuesta"
                                            }
                                        }
                                    }
                                },
                                enabled = !probandoConexion
                            ) {
                                Text(if (probandoConexion) "Probando..." else "Probar IP")
                            }

                            Text(
                                text = estadoConexion,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (esConexionValida) Color(0xFF388E3C) else Color(0xFFD32F2F)
                            )
                        }
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("Hora de la Alarma", fontWeight = FontWeight.Bold)
                                Text(text = horaSeleccionada, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
                            }

                            Row {
                                IconButton(onClick = { timePickerDialog.show() }) {
                                    Icon(Icons.Filled.AccessTime, contentDescription = "Hora", tint = MaterialTheme.colorScheme.primary)
                                }
                                Button(onClick = {
                                    if (!esConexionValida) {
                                        Toast.makeText(ctx, "Debes conectar la IP primero", Toast.LENGTH_LONG).show()
                                    } else if (diasSeleccionados.isEmpty()) {
                                        Toast.makeText(ctx, "Selecciona al menos un día", Toast.LENGTH_SHORT).show()
                                    } else {
                                        val strDias = diasMap.map { it.second }.filter { diasSeleccionados.contains(it) }.joinToString(", ")
                                        scope.launch {
                                            dao.crearAlarma(Alarma(usuarioOwner = usuarioActual, hora = horaSeleccionada, dias = strDias, ipDestino = ip))
                                            listaAlarmas = dao.obtenerAlarmas(usuarioActual)
                                            Toast.makeText(ctx, "Alarma guardada", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }) {
                                    Text("Guardar", fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        Text("Repetir días:", fontSize = 12.sp, fontWeight = FontWeight.Bold)

                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            diasMap.forEach { (letra, nombreLargo) ->
                                val sel = diasSeleccionados.contains(nombreLargo)
                                FilterChip(
                                    selected = sel,
                                    onClick = {
                                        diasSeleccionados = if (sel) diasSeleccionados - nombreLargo else diasSeleccionados + nombreLargo
                                    },
                                    label = { Text(text = letra, fontSize = 12.sp, fontWeight = FontWeight.Bold) },
                                    modifier = Modifier.width(42.dp).height(38.dp)
                                )
                            }
                        }
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Comando Directo a Receptor", fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            Button(
                                onClick = {
                                    scope.launch(Dispatchers.IO) { enviarComandoTCP(ip, "ENCENDER") }
                                    Toast.makeText(ctx, "Señal 'ENCENDER' enviada", Toast.LENGTH_SHORT).show()
                                },
                                enabled = esConexionValida,
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
                            ) {
                                Icon(Icons.Filled.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Encender", fontWeight = FontWeight.Bold)
                            }

                            Button(
                                onClick = {
                                    scope.launch(Dispatchers.IO) { enviarComandoTCP(ip, "APAGAR") }
                                    Toast.makeText(ctx, "Señal 'APAGAR' enviada", Toast.LENGTH_SHORT).show()
                                },
                                enabled = esConexionValida
                            ) {
                                Icon(Icons.Filled.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Apagar", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            item {
                Text(text = "Mis Alarmas Guardadas", fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.padding(bottom = 8.dp))
            }

            if (listaAlarmas.isEmpty()) {
                item { Text("No hay alarmas registradas.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                items(listaAlarmas) { alarma ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(text = "⏰ ${alarma.hora}", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                Text(text = "📅 Días: ${alarma.dias}", fontSize = 12.sp)
                                Text(text = "👤 Usuario: ${alarma.usuarioOwner}", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                                Text(text = "🌐 IP: ${alarma.ipDestino}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = {
                                scope.launch {
                                    dao.eliminarAlarma(alarma.id)
                                    listaAlarmas = dao.obtenerAlarmas(usuarioActual)
                                }
                            }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Eliminar", tint = Color(0xFFD32F2F))
                            }
                        }
                    }
                }
            }
        }
    }
}

fun enviarComandoTCP(ip: String, comando: String) {
    try {
        val socket = Socket(ip, 8080)
        PrintWriter(socket.getOutputStream(), true).println(comando)
        socket.close()
    } catch (_: Exception) {}
}

suspend fun probarSocket(ip: String, puerto: Int): Boolean {
    return try {
        val socket = Socket()
        socket.connect(InetSocketAddress(ip, puerto), 1500)
        socket.close()
        true
    } catch (e: Exception) {
        false
    }
}
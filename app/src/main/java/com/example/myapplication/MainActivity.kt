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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
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
import java.text.SimpleDateFormat
import java.util.*

@Entity data class Usuario(
    @PrimaryKey val user: String,
    val pass: String,
    val nombre: String,
    val email: String,
    val telefono: String
)

@Entity data class Alarma(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val usuarioOwner: String,
    val hora: String,
    val dias: String,
    val ipDestino: String,
    val activa: Boolean = true
)

@Entity data class Historial(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val usuarioOwner: String,
    val descripcion: String,
    val fecha: String
)

@Dao
interface AppDao {
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun registrarUser(u: Usuario)
    @Query("SELECT * FROM Usuario WHERE user = :u LIMIT 1") suspend fun buscarUsuario(u: String): Usuario?

    @Query("SELECT * FROM Usuario WHERE user = :identifier OR email = :identifier LIMIT 1")
    suspend fun buscarPorUsuarioOCorreo(identifier: String): Usuario?

    @Query("SELECT * FROM Usuario WHERE telefono = :tel")
    suspend fun buscarPorTelefono(tel: String): List<Usuario>

    @Query("SELECT * FROM Usuario WHERE (user = :identifier OR email = :identifier) AND pass = :p LIMIT 1")
    suspend fun login(identifier: String, p: String): Usuario?

    @Query("UPDATE Usuario SET pass = :nuevaPass WHERE user = :usuario") suspend fun actualizarPassword(usuario: String, nuevaPass: String)

    @Insert suspend fun crearAlarma(a: Alarma)
    @Query("SELECT * FROM Alarma WHERE usuarioOwner = :u ORDER BY id DESC") suspend fun obtenerAlarmas(u: String): List<Alarma>
    @Query("DELETE FROM Alarma WHERE id = :id") suspend fun eliminarAlarma(id: Int)

    @Insert suspend fun insertarHistorial(h: Historial)
    @Query("SELECT * FROM Historial WHERE usuarioOwner = :u ORDER BY id DESC") suspend fun obtenerHistorial(u: String): List<Historial>
}

@Database(entities = [Usuario::class, Alarma::class, Historial::class], version = 15, exportSchema = false)
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

fun validarPasswordSegura(password: String): Boolean {
    val tieneMayuscula = password.any { it.isUpperCase() }
    val tieneMinuscula = password.any { it.isLowerCase() }
    val tieneNumero = password.any { it.isDigit() }
    val esLargaSuficiente = password.length >= 6
    return tieneMayuscula && tieneMinuscula && tieneNumero && esLargaSuficiente
}

fun obtenerFechaActual(): String {
    val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
    return sdf.format(Date())
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                        composable("problemas_login") { ProblemasLoginScreen(dao, nav) }
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
    val scrollState = rememberScrollState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .imePadding() // Empuja el contenido hacia arriba cuando sale el teclado
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp), // Permite que la tarjeta se adapte y scrolle si hay teclado
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .verticalScroll(scrollState),
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
                    label = { Text("Usuario o Correo") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                )

                OutlinedTextField(
                    value = p,
                    onValueChange = { p = it },
                    label = { Text("Contraseña") },
                    singleLine = true,
                    visualTransformation = if (passVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
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
                                dao.insertarHistorial(Historial(usuarioOwner = userValido.user, descripcion = "Inicio de sesión exitoso", fecha = obtenerFechaActual()))
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

                Spacer(modifier = Modifier.height(12.dp))

                TextButton(
                    onClick = { nav.navigate("registro") },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("¿No tienes cuenta? Regístrate", color = MaterialTheme.colorScheme.primary)
                }

                TextButton(
                    onClick = { nav.navigate("problemas_login") },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("¿Problemas para iniciar sesión?", color = MaterialTheme.colorScheme.secondary)
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), thickness = 1.dp)
                Spacer(modifier = Modifier.height(8.dp))

                TextButton(
                    onClick = {
                        scope.launch {
                            dao.insertarHistorial(Historial(usuarioOwner = "Invitado", descripcion = "Ingreso como invitado", fecha = obtenerFechaActual()))
                            nav.navigate("panel/Invitado")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Continuar como invitado", color = MaterialTheme.colorScheme.outline, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegistroScreen(nav: NavHostController, dao: AppDao) {
    var u by remember { mutableStateOf("") }
    var nombre by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var telefono by remember { mutableStateOf("") }
    var p by remember { mutableStateOf("") }
    var passVisible by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Registro") },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Volver")
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding() // Empuja el contenido hacia arriba cuando sale el teclado
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 550.dp), // Altura adaptativa con scroll
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(
                    modifier = Modifier
                        .padding(24.dp)
                        .verticalScroll(scrollState),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Registro de Usuario",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    OutlinedTextField(
                        value = u,
                        onValueChange = { u = it },
                        label = { Text("Nuevo Usuario") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
                    )

                    OutlinedTextField(
                        value = nombre,
                        onValueChange = { nombre = it },
                        label = { Text("Nombre Completo") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
                    )

                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text("Correo Electrónico") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
                    )

                    OutlinedTextField(
                        value = telefono,
                        onValueChange = { telefono = it },
                        label = { Text("Teléfono") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
                    )

                    OutlinedTextField(
                        value = p,
                        onValueChange = { p = it },
                        label = { Text("Contraseña (Min. 6 car, Mayús, Núm)") },
                        singleLine = true,
                        visualTransformation = if (passVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
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
                            val nombreLimpio = nombre.trim()
                            val emailLimpio = email.trim()
                            val telLimpio = telefono.trim()

                            if (usuarioLimpio.isNotBlank() && nombreLimpio.isNotBlank() && p.isNotBlank() && emailLimpio.isNotBlank() && telLimpio.isNotBlank()) {
                                if (!emailLimpio.contains("@") || !emailLimpio.contains(".")) {
                                    Toast.makeText(ctx, "Ingresa un correo electrónico válido", Toast.LENGTH_SHORT).show()
                                } else if (!validarPasswordSegura(p)) {
                                    Toast.makeText(ctx, "La contraseña debe tener al menos 6 caracteres, una mayúscula y un número", Toast.LENGTH_LONG).show()
                                } else {
                                    scope.launch {
                                        val existe = dao.buscarUsuario(usuarioLimpio)
                                        if (existe != null) {
                                            Toast.makeText(ctx, "El usuario ya existe", Toast.LENGTH_SHORT).show()
                                        } else {
                                            dao.registrarUser(Usuario(usuarioLimpio, hashPassword(p), nombreLimpio, emailLimpio, telLimpio))
                                            dao.insertarHistorial(Historial(usuarioOwner = usuarioLimpio, descripcion = "Cuenta creada exitosamente", fecha = obtenerFechaActual()))
                                            Toast.makeText(ctx, "Registrado con éxito", Toast.LENGTH_SHORT).show()
                                            nav.popBackStack()
                                        }
                                    }
                                }
                            } else {
                                Toast.makeText(ctx, "Completa todos los campos", Toast.LENGTH_SHORT).show()
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProblemasLoginScreen(dao: AppDao, nav: NavHostController) {
    var selectedTab by remember { mutableStateOf(0) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    var telefonoBusqueda by remember { mutableStateOf("") }
    var listaUsuariosEncontrados by remember { mutableStateOf(listOf<Usuario>()) }
    var mensajeBusqueda by remember { mutableStateOf("") }

    var identificadorPass by remember { mutableStateOf("") }
    var telefonoPass by remember { mutableStateOf("") }
    var passNueva by remember { mutableStateOf("") }
    var passVisible by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ayuda de Acceso") },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Volver")
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding() // Empuja el contenido hacia arriba cuando sale el teclado
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 550.dp), // Altura adaptativa con scroll
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(
                    modifier = Modifier
                        .padding(20.dp)
                        .verticalScroll(scrollState),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Problemas para iniciar sesión",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    TabRow(selectedTabIndex = selectedTab) {
                        Tab(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            text = { Text("Olvidé mi usuario") }
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            text = { Text("Olvidé mi contraseña") }
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    if (selectedTab == 0) {
                        OutlinedTextField(
                            value = telefonoBusqueda,
                            onValueChange = { telefonoBusqueda = it },
                            label = { Text("Número de Teléfono Registrado") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                        )

                        Button(
                            onClick = {
                                val telLimpio = telefonoBusqueda.trim()
                                if (telLimpio.isNotBlank()) {
                                    scope.launch {
                                        val usuarios = dao.buscarPorTelefono(telLimpio)
                                        if (usuarios.isNotEmpty()) {
                                            listaUsuariosEncontrados = usuarios
                                            mensajeBusqueda = "Cuentas asociadas encontradas:"
                                        } else {
                                            listaUsuariosEncontrados = emptyList()
                                            mensajeBusqueda = "No se encontró ninguna cuenta asociada a este teléfono."
                                        }
                                    }
                                } else {
                                    Toast.makeText(ctx, "Ingresa tu número de teléfono", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Buscar Usuario", fontWeight = FontWeight.Bold)
                        }

                        if (mensajeBusqueda.isNotBlank()) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = mensajeBusqueda,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.secondary
                            )
                            Spacer(modifier = Modifier.height(8.dp))

                            listaUsuariosEncontrados.forEach { usr ->
                                Card(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                                ) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Text("Usuario: ${usr.user}", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                        Text("Correo: ${usr.email}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }

                    } else {
                        OutlinedTextField(
                            value = identificadorPass,
                            onValueChange = { identificadorPass = it },
                            label = { Text("Usuario o Correo") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
                        )

                        OutlinedTextField(
                            value = telefonoPass,
                            onValueChange = { telefonoPass = it },
                            label = { Text("Teléfono de Registro") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
                        )

                        OutlinedTextField(
                            value = passNueva,
                            onValueChange = { passNueva = it },
                            label = { Text("Nueva Contraseña (Mín. 6 car, Mayús, Núm)") },
                            singleLine = true,
                            visualTransformation = if (passVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            trailingIcon = {
                                IconButton(onClick = { passVisible = !passVisible }) {
                                    Icon(
                                        imageVector = if (passVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                                        contentDescription = "Mostrar/Ocultar"
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                        )

                        Button(
                            onClick = {
                                val idLimpio = identificadorPass.trim()
                                val telLimpio = telefonoPass.trim()
                                if (idLimpio.isNotBlank() && telLimpio.isNotBlank() && passNueva.isNotBlank()) {
                                    if (!validarPasswordSegura(passNueva)) {
                                        Toast.makeText(ctx, "La contraseña debe tener mín. 6 car., una mayúscula y un número", Toast.LENGTH_LONG).show()
                                    } else {
                                        scope.launch {
                                            val usuarioEncontrado = dao.buscarPorUsuarioOCorreo(idLimpio)
                                            if (usuarioEncontrado != null && usuarioEncontrado.telefono == telLimpio) {
                                                dao.actualizarPassword(usuarioEncontrado.user, hashPassword(passNueva))
                                                dao.insertarHistorial(Historial(usuarioOwner = usuarioEncontrado.user, descripcion = "Contraseña restablecida por recuperación", fecha = obtenerFechaActual()))
                                                Toast.makeText(ctx, "¡Contraseña actualizada con éxito!", Toast.LENGTH_SHORT).show()
                                                nav.popBackStack()
                                            } else {
                                                Toast.makeText(ctx, "Los datos de verificación no coinciden", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                } else {
                                    Toast.makeText(ctx, "Completa todos los campos", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Restablecer Clave", fontWeight = FontWeight.Bold)
                        }
                    }
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
    var listaHistorial by remember { mutableStateOf(listOf<Historial>()) }

    var tabSeleccionada by remember { mutableStateOf(0) }

    val scope = rememberCoroutineScope()
    val esConexionValida = estadoConexion == "Conectado"

    LaunchedEffect(usuarioActual) {
        listaAlarmas = dao.obtenerAlarmas(usuarioActual)
        listaHistorial = dao.obtenerHistorial(usuarioActual)
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
                    TextButton(onClick = {
                        scope.launch {
                            dao.insertarHistorial(Historial(usuarioOwner = usuarioActual, descripcion = "Cierre de sesión", fecha = obtenerFechaActual()))
                            nav.navigate("login") { popUpTo(0) }
                        }
                    }) {
                        Text("Salir", color = Color(0xFFD32F2F), fontWeight = FontWeight.Bold)
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tabSeleccionada == 0,
                    onClick = { tabSeleccionada = 0 },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = "Configuración") },
                    label = { Text("Configuración") }
                )
                NavigationBarItem(
                    selected = tabSeleccionada == 1,
                    onClick = {
                        tabSeleccionada = 1
                        scope.launch {
                            listaHistorial = dao.obtenerHistorial(usuarioActual)
                        }
                    },
                    icon = { Icon(Icons.Filled.List, contentDescription = "Historial") },
                    label = { Text("Historial") }
                )
            }
        }
    ) { innerPadding ->
        if (tabSeleccionada == 0) {
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
                                                dao.insertarHistorial(Historial(usuarioOwner = usuarioActual, descripcion = "Prueba de conexión IP $ip: ${if (exito) "Éxito" else "Fallo"}", fecha = obtenerFechaActual()))
                                                listaHistorial = dao.obtenerHistorial(usuarioActual)
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
                                                dao.insertarHistorial(Historial(usuarioOwner = usuarioActual, descripcion = "Alarma guardada a las $horaSeleccionada ($strDias)", fecha = obtenerFechaActual()))
                                                listaAlarmas = dao.obtenerAlarmas(usuarioActual)
                                                listaHistorial = dao.obtenerHistorial(usuarioActual)
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
                                        scope.launch(Dispatchers.IO) {
                                            enviarComandoTCP(ip, "ENCENDER")
                                            dao.insertarHistorial(Historial(usuarioOwner = usuarioActual, descripcion = "Comando TCP enviado: ENCENDER", fecha = obtenerFechaActual()))
                                        }
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
                                        scope.launch(Dispatchers.IO) {
                                            enviarComandoTCP(ip, "APAGAR")
                                            dao.insertarHistorial(Historial(usuarioOwner = usuarioActual, descripcion = "Comando TCP enviado: APAGAR", fecha = obtenerFechaActual()))
                                        }
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
                                        dao.insertarHistorial(Historial(usuarioOwner = usuarioActual, descripcion = "Alarma eliminada (${alarma.hora})", fecha = obtenerFechaActual()))
                                        listaAlarmas = dao.obtenerAlarmas(usuarioActual)
                                        listaHistorial = dao.obtenerHistorial(usuarioActual)
                                    }
                                }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Eliminar", tint = Color(0xFFD32F2F))
                                }
                            }
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 16.dp),
                contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)
            ) {
                item {
                    Text(text = "Historial de Acciones", fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(bottom = 12.dp))
                }

                if (listaHistorial.isEmpty()) {
                    item { Text("No hay acciones registradas en el historial.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                } else {
                    items(listaHistorial) { itemHistorial ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Text(text = itemHistorial.descripcion, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(text = "🕒 ${itemHistorial.fecha}", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
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

fun probarSocket(ip: String, puerto: Int): Boolean {
    return try {
        val socket = Socket()
        socket.connect(InetSocketAddress(ip, puerto), 1500)
        socket.close()
        true
    } catch (e: Exception) {
        false
    }
}
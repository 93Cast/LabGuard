
package com.example.labguard.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.labguard.OnnxModelManager
import com.example.labguard.data.LabRepository

@Composable
fun LabGuardApp(
    repository: LabRepository,
    modelManager: OnnxModelManager?
) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = "home"
    ) {
        composable("home") {
            HomeScreen(
                repository = repository,
                onRegister = {
                    navController.navigate("employee")
                }
            )
        }

        composable("employee") {
            EmployeeScreen(
                onNext = { name, card ->
                    navController.navigate(
                        "verify/${Uri.encode(name)}/${Uri.encode(card)}"
                    )
                },
                onBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(
            route = "verify/{name}/{card}",
            arguments = listOf(
                navArgument("name") { type = NavType.StringType },
                navArgument("card") { type = NavType.StringType }
            )
        ) { entry ->
            val name = Uri.decode(
                entry.arguments?.getString("name").orEmpty()
            )
            val card = Uri.decode(
                entry.arguments?.getString("card").orEmpty()
            )

            VerificationScreen(
                employeeName = name,
                employeeCard = card,
                repository = repository,
                modelManager = modelManager,
                onFinish = {
                    navController.popBackStack(
                        route = "home",
                        inclusive = false
                    )
                },
                onBack = {
                    navController.popBackStack()
                }
            )
        }
    }
}

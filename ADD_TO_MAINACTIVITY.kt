// ============================================
// HOW TO ADD HEALTH SCREEN TO YOUR MAINACTIVITY
// ============================================

// Step 1: Add this import at the top of MainActivity.kt
import com.sameerasw.airsync.health.SimpleHealthScreen

// Step 2: In your NavHost, add this route:
composable("health") {
    SimpleHealthScreen(
        onNavigateBack = { navController.popBackStack() }
    )
}

// Step 3: Add a button somewhere in your UI to navigate to it
// Example 1: Simple Button
Button(
    onClick = { navController.navigate("health") },
    modifier = Modifier.fillMaxWidth()
) {
    Icon(Icons.Default.FavoriteBorder, contentDescription = null)
    Spacer(modifier = Modifier.width(8.dp))
    Text("Health & Fitness")
}

// Example 2: Card with Icon
Card(
    modifier = Modifier
        .fillMaxWidth()
        .clickable { navController.navigate("health") }
) {
    Row(
        modifier = Modifier.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.FavoriteBorder,
            contentDescription = null,
            modifier = Modifier.size(32.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column {
            Text(
                "Health & Fitness",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                "View your health data from Google Fit and Samsung Health",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        Icon(
            Icons.Default.ChevronRight,
            contentDescription = null
        )
    }
}

// Example 3: ListItem (for settings screen)
ListItem(
    headlineContent = { Text("Health & Fitness") },
    supportingContent = { Text("Sync health data with Mac") },
    leadingContent = {
        Icon(Icons.Default.FavoriteBorder, contentDescription = null)
    },
    trailingContent = {
        Icon(Icons.Default.ChevronRight, contentDescription = null)
    },
    modifier = Modifier.clickable {
        navController.navigate("health")
    }
)

// Example 4: TopAppBar Action Button
TopAppBar(
    title = { Text("AirSync") },
    actions = {
        IconButton(onClick = { navController.navigate("health") }) {
            Icon(Icons.Default.FavoriteBorder, contentDescription = "Health")
        }
    }
)

// ============================================
// THAT'S IT! Just add the route and a button.
// The Health screen will handle everything else:
// - Checking if Health Connect is installed
// - Requesting permissions
// - Fetching and displaying health data
// ============================================

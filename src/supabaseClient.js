// Import the createClient function from the Supabase JS library
import { createClient } from "@supabase/supabase-js";

// ====== CONFIGURATION ======
// REPLACE THESE VALUES WITH YOUR OWN SUPABASE PROJECT DETAILS IF NEEDED

// Your Supabase project URL (found in project settings -> API)
const SUPABASE_URL = "https://ykbwzodadijtiizobehk.supabase.co";

// Your Supabase public/anonymous API key (found in project settings -> API)
const SUPABASE_PUBLIC_KEY = "sb_publishable_0TnekhtKdN_MUcTiZ66sZA_qGjqqFyh";
// ===========================

// Initialize and export the single Supabase client instance for use across the application
export const supabase = createClient(SUPABASE_URL, SUPABASE_PUBLIC_KEY);

using System.Globalization;

namespace EasyJoy;

/// <summary>UI language, from the Windows display language: Portuguese, Spanish, or English for anything else.</summary>
static class L
{
    static readonly string Lang = CultureInfo.CurrentUICulture.TwoLetterISOLanguageName switch
    {
        "pt" => "pt",
        "es" => "es",
        _ => "en",
    };

    public static string T(string pt, string en, string es) => Lang switch
    {
        "pt" => pt,
        "es" => es,
        _ => en,
    };
}

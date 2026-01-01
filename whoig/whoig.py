#!/usr/bin/env python3
"""
WhoIG - Instagram OSINT Tool
Analyze Instagram profiles for trust assessment, scam detection, and contact extraction.
"""

import argparse
import json
import re
import sys
import time
from dataclasses import dataclass, field
from datetime import datetime
from typing import Optional, List, Tuple
from urllib.parse import quote

import requests
requests.packages.urllib3.disable_warnings(requests.packages.urllib3.exceptions.InsecureRequestWarning)


@dataclass
class PhoneAnalysis:
    raw: Optional[str] = None
    country: Optional[str] = None
    country_code: Optional[str] = None
    risk_level: Optional[str] = None  # very_high, high, moderate, trusted
    phone_type: Optional[str] = None  # mobile, landline, unknown
    carrier_hint: Optional[str] = None
    visible_digits: Optional[str] = None
    phone_format: Optional[str] = None
    operator_range: Optional[str] = None
    score_modifier: int = 0


@dataclass
class EmailAnalysis:
    raw: Optional[str] = None
    provider: Optional[str] = None
    provider_confidence: Optional[str] = None  # high, medium, low
    domain_tld: Optional[str] = None
    domain_type: Optional[str] = None  # free, business, edu, gov
    security_level: Optional[str] = None  # high, medium, low
    username_length_estimate: Optional[str] = None
    username_first_char: Optional[str] = None
    username_last_char: Optional[str] = None
    score_modifier: int = 0


@dataclass
class BioAnalysis:
    emails: List[str] = field(default_factory=list)
    phones: List[str] = field(default_factory=list)
    urls: List[str] = field(default_factory=list)
    scam_indicators: List[str] = field(default_factory=list)
    risk_level: Optional[str] = None


@dataclass
class AdditionalInfo:
    """Info from additional API endpoints"""
    public_email: Optional[str] = None
    public_phone: Optional[str] = None
    phone_country_code: Optional[str] = None
    city_name: Optional[str] = None
    account_type: Optional[int] = None  # 1=personal, 2=business, 3=creator


@dataclass
class InstagramProfile:
    username: str
    user_id: Optional[str] = None
    full_name: Optional[str] = None
    biography: Optional[str] = None
    external_url: Optional[str] = None
    profile_pic_url: Optional[str] = None
    followers: Optional[int] = None
    following: Optional[int] = None
    posts_count: Optional[int] = None
    is_private: bool = False
    is_verified: bool = False
    is_business: bool = False
    business_category: Optional[str] = None
    business_email: Optional[str] = None
    business_phone: Optional[str] = None
    business_address: Optional[str] = None
    obfuscated_email: Optional[str] = None
    obfuscated_phone: Optional[str] = None
    phone_analysis: Optional[PhoneAnalysis] = None
    email_analysis: Optional[EmailAnalysis] = None
    bio_analysis: Optional[BioAnalysis] = None
    additional_info: Optional[AdditionalInfo] = None
    first_post_date: Optional[str] = None
    estimated_creation: Optional[str] = None
    wayback_first_archive: Optional[str] = None
    wayback_url: Optional[str] = None
    trust_score: int = 0
    trust_details: List[Tuple[str, int, str]] = field(default_factory=list)
    has_highlight_reels: bool = False
    has_guides: bool = False
    has_channel: bool = False
    is_professional_account: bool = False
    is_supervision_enabled: bool = False
    pronouns: List[str] = field(default_factory=list)
    bio_links: List[str] = field(default_factory=list)
    profile_pic_id: Optional[str] = None
    has_videos: bool = False
    has_clips: bool = False
    total_igtv_videos: Optional[int] = None
    account_badges: List[str] = field(default_factory=list)
    is_memorialized: bool = False


class Colors:
    GREEN = "\033[92m"
    YELLOW = "\033[93m"
    BLUE = "\033[94m"
    CYAN = "\033[96m"
    RED = "\033[91m"
    PURPLE = "\033[95m"
    BOLD = "\033[1m"
    DIM = "\033[2m"
    RESET = "\033[0m"


# ══════════════════════════════════════════════════════════════════════════════
# PHONE ANALYSIS DATA
# ══════════════════════════════════════════════════════════════════════════════

HIGH_RISK_PHONE_PREFIXES = {
    # West Africa - VERY HIGH RISK (brouteurs)
    "+225": ("Côte d'Ivoire", "very_high", -20),
    "+229": ("Bénin", "very_high", -20),
    "+228": ("Togo", "very_high", -20),
    "+233": ("Ghana", "very_high", -20),
    "+234": ("Nigeria", "very_high", -20),
    "+221": ("Sénégal", "high", -12),
    "+223": ("Mali", "high", -12),
    "+226": ("Burkina Faso", "high", -12),
    "+227": ("Niger", "high", -12),
    "+220": ("Gambie", "high", -12),
    "+224": ("Guinée", "high", -12),
    "+232": ("Sierra Leone", "high", -12),
    "+231": ("Liberia", "high", -12),
    "+237": ("Cameroun", "high", -12),
    "+243": ("RD Congo", "high", -12),
    "+241": ("Gabon", "moderate", -5),
    "+242": ("Congo", "moderate", -5),
    "+212": ("Maroc", "moderate", -5),
    "+216": ("Tunisie", "moderate", -5),
    # Eastern Europe
    "+380": ("Ukraine", "moderate", -5),
    "+375": ("Biélorussie", "moderate", -5),
    # Southeast Asia scam compounds
    "+95": ("Myanmar", "high", -12),
    "+856": ("Laos", "high", -12),
    "+855": ("Cambodge", "moderate", -5),
    "+63": ("Philippines", "moderate", -5),
    "+84": ("Vietnam", "moderate", -5),
}

TRUSTED_PHONE_PREFIXES = {
    "+33": "France", "+1": "USA/Canada", "+44": "UK", "+49": "Allemagne",
    "+34": "Espagne", "+39": "Italie", "+41": "Suisse", "+32": "Belgique",
    "+31": "Pays-Bas", "+43": "Autriche", "+81": "Japon", "+82": "Corée du Sud",
    "+61": "Australie", "+64": "Nouvelle-Zélande", "+46": "Suède", "+47": "Norvège",
    "+45": "Danemark", "+358": "Finlande", "+353": "Irlande", "+351": "Portugal",
    "+48": "Pologne", "+420": "Tchéquie", "+7": "Russie", "+86": "Chine",
    "+91": "Inde", "+55": "Brésil", "+52": "Mexique", "+65": "Singapour",
    "+852": "Hong Kong", "+971": "UAE", "+966": "Arabie Saoudite",
}

# Phone type patterns by country (mobile prefixes)
PHONE_PATTERNS = {
    "+33": {
        "mobile": ["6", "7"],
        "landline": ["1", "2", "3", "4", "5"],
        "format": "+33 X XX XX XX XX",
        "operators": {
            "6": {"60-63": "Orange", "64-65": "SFR", "66-67": "Bouygues", "68-69": "Mixed"},
            "7": {"70-73": "Free Mobile", "74-79": "MVNOs/New"}
        }
    },
    "+44": {
        "mobile": ["7"],
        "landline": ["1", "2", "3"],
        "format": "+44 7XXX XXXXXX",
        "areas": {"20": "London", "121": "Birmingham", "131": "Edinburgh", "141": "Glasgow"}
    },
    "+49": {
        "mobile": ["15", "16", "17"],
        "landline": ["2", "3", "4", "5", "6", "7", "8", "9"],
        "format": "+49 1XX XXXXXXXX",
        "operators": {
            "151": "T-Mobile", "160": "T-Mobile", "170": "T-Mobile", "171": "T-Mobile", "175": "T-Mobile",
            "152": "Vodafone", "162": "Vodafone", "172": "Vodafone", "173": "Vodafone", "174": "Vodafone",
            "155": "O2", "157": "O2", "159": "O2", "176": "O2", "179": "O2",
            "163": "E-Plus", "177": "E-Plus", "178": "E-Plus"
        }
    },
    "+1": {
        "format": "+1 XXX XXX XXXX",
        "areas": {
            "212": "New York", "213": "Los Angeles", "312": "Chicago", "415": "San Francisco",
            "305": "Miami", "416": "Toronto", "514": "Montreal", "604": "Vancouver"
        }
    },
    "+39": {"mobile": ["3"], "landline": ["0"], "format": "+39 3XX XXX XXXX"},
    "+34": {"mobile": ["6", "7"], "landline": ["9"], "format": "+34 6XX XXX XXX"},
    "+31": {"mobile": ["6"], "landline": ["1", "2", "3", "4", "5", "7"], "format": "+31 6 XXXX XXXX"},
    "+32": {"mobile": ["4"], "landline": ["1", "2", "3", "5", "6", "7", "8", "9"], "format": "+32 4XX XX XX XX"},
    "+41": {"mobile": ["7"], "landline": ["2", "3", "4", "5", "6"], "format": "+41 7X XXX XX XX"},
}

# ══════════════════════════════════════════════════════════════════════════════
# EMAIL ANALYSIS DATA
# ══════════════════════════════════════════════════════════════════════════════

EMAIL_PROVIDERS = {
    # Major providers
    "gmail.com": ("Gmail", "Google", "high", "free"),
    "googlemail.com": ("Gmail", "Google", "high", "free"),
    "yahoo.com": ("Yahoo", "Yahoo", "medium", "free"),
    "yahoo.fr": ("Yahoo France", "Yahoo", "medium", "free"),
    "yahoo.co.uk": ("Yahoo UK", "Yahoo", "medium", "free"),
    "outlook.com": ("Outlook", "Microsoft", "high", "free"),
    "hotmail.com": ("Hotmail", "Microsoft", "medium", "free"),
    "hotmail.fr": ("Hotmail France", "Microsoft", "medium", "free"),
    "live.com": ("Live", "Microsoft", "medium", "free"),
    "msn.com": ("MSN", "Microsoft", "medium", "free"),
    "icloud.com": ("iCloud", "Apple", "high", "free"),
    "me.com": ("iCloud", "Apple", "high", "free"),
    "mac.com": ("iCloud", "Apple", "high", "free"),
    # Secure providers
    "protonmail.com": ("ProtonMail", "Proton", "very_high", "secure"),
    "proton.me": ("ProtonMail", "Proton", "very_high", "secure"),
    "tutanota.com": ("Tutanota", "Tutanota", "very_high", "secure"),
    "tutamail.com": ("Tutanota", "Tutanota", "very_high", "secure"),
    # French providers
    "orange.fr": ("Orange", "Orange FR", "medium", "isp"),
    "wanadoo.fr": ("Wanadoo", "Orange FR", "medium", "isp"),
    "free.fr": ("Free", "Free FR", "medium", "isp"),
    "sfr.fr": ("SFR", "SFR FR", "medium", "isp"),
    "laposte.net": ("LaPoste", "LaPoste FR", "medium", "free"),
    "bbox.fr": ("Bbox", "Bouygues FR", "medium", "isp"),
    # German providers
    "gmx.com": ("GMX", "GMX", "medium", "free"),
    "gmx.de": ("GMX DE", "GMX", "medium", "free"),
    "web.de": ("Web.de", "Web.de", "medium", "free"),
    "t-online.de": ("T-Online", "Deutsche Telekom", "medium", "isp"),
    # Other
    "aol.com": ("AOL", "AOL", "low", "free"),
    "mail.ru": ("Mail.ru", "Mail.ru", "low", "free"),
    "yandex.ru": ("Yandex", "Yandex", "low", "free"),
    "yandex.com": ("Yandex", "Yandex", "low", "free"),
}

SCAM_PATTERNS = [
    (r"\b(invest|trading|forex|crypto|bitcoin|btc|eth|nft)\b", "Investment/Crypto", 15),
    (r"\b(make money|earn money|income|profit|roi|returns)\b", "Money-making claims", 15),
    (r"\b(\d+k|\d+\$|\$\d+|€\d+|\d+€)\s*(per|a|/)?\s*(day|week|month)\b", "Income claims", 20),
    (r"\b(passive income|financial freedom|get rich|millionaire)\b", "Get-rich-quick", 20),
    (r"\b(single|lonely|looking for love|soulmate|true love)\b", "Romance bait", 15),
    (r"\b(widow|widower|divorced|lost my|passed away)\b", "Sympathy story", 20),
    (r"\b(god.?fearing|honest|loyal|faithful|trustworthy)\b", "Trust-building", 10),
    (r"\b(dm|message|contact|text|whatsapp|telegram)\s*(me|for|now)\b", "Contact request", 10),
    (r"\b(link in bio|click link|check link|tap link)\b", "Link pushing", 8),
    (r"\b(limited time|act now|don't miss|last chance|hurry)\b", "Urgency tactics", 12),
    (r"\b(hiring|job opportunity|work from home|remote job)\b", "Job offer", 8),
    (r"\b(hack|hacker|recovery|recover account|unlock)\b", "Hacking services", 25),
    (r"\b(spell|love spell|voodoo|psychic|fortune)\b", "Supernatural", 20),
    (r"\b(beneficiary|inheritance|lottery|won|winner|claim)\b", "Lottery scam", 25),
    (r"\b(army|military|soldier|deployed|overseas)\b", "Military scam", 15),
    (r"\b(oil rig|offshore|engineer|contractor)\b", "Oil rig scam", 20),
]

SUSPICIOUS_URLS = ["bit.ly", "tinyurl", "t.co", "goo.gl", "ow.ly", "is.gd", "buff.ly", "tiny.cc", "rb.gy"]

CROSS_PLATFORM_URLS = [
    ("Twitter/X", "https://twitter.com/{u}"), ("TikTok", "https://tiktok.com/@{u}"),
    ("Facebook", "https://facebook.com/{u}"), ("YouTube", "https://youtube.com/@{u}"),
    ("LinkedIn", "https://linkedin.com/in/{u}"), ("Snapchat", "https://snapchat.com/add/{u}"),
    ("Pinterest", "https://pinterest.com/{u}"), ("Reddit", "https://reddit.com/user/{u}"),
    ("GitHub", "https://github.com/{u}"), ("Twitch", "https://twitch.tv/{u}"),
    ("Telegram", "https://t.me/{u}"), ("OnlyFans", "https://onlyfans.com/{u}"),
]


# ══════════════════════════════════════════════════════════════════════════════
# ANALYSIS FUNCTIONS
# ══════════════════════════════════════════════════════════════════════════════

def analyze_phone(phone: Optional[str]) -> PhoneAnalysis:
    """Enhanced phone number analysis with type, carrier, and region detection."""
    result = PhoneAnalysis(raw=phone)
    if not phone:
        return result
    
    phone = phone.strip()
    result.visible_digits = ''.join(c for c in phone if c.isdigit())
    
    # Check high-risk prefixes first
    for prefix, (country, risk, score) in HIGH_RISK_PHONE_PREFIXES.items():
        if phone.startswith(prefix):
            result.country, result.country_code = country, prefix
            result.risk_level, result.score_modifier = risk, score
            break
    
    # Check trusted prefixes
    if not result.country:
        for prefix, country in TRUSTED_PHONE_PREFIXES.items():
            if phone.startswith(prefix):
                result.country, result.country_code = country, prefix
                result.risk_level, result.score_modifier = "trusted", 3
                break
    
    # Detailed analysis based on country
    if result.country_code and result.country_code in PHONE_PATTERNS:
        patterns = PHONE_PATTERNS[result.country_code]
        after_code = phone[len(result.country_code):].strip().replace(" ", "").replace("-", "")
        
        # Set format
        result.phone_format = patterns.get("format")
        
        if after_code:
            first_digit = after_code[0] if after_code else ""
            first_two = after_code[:2] if len(after_code) >= 2 else first_digit
            first_three = after_code[:3] if len(after_code) >= 3 else first_two
            
            # Detect phone type
            if "mobile" in patterns:
                if first_digit in patterns["mobile"] or first_two in patterns["mobile"]:
                    result.phone_type = "mobile"
            if not result.phone_type and "landline" in patterns:
                if first_digit in patterns["landline"]:
                    result.phone_type = "landline"
            
            # Detect operator/carrier
            if "operators" in patterns:
                ops = patterns["operators"]
                if isinstance(ops, dict):
                    # Check by prefix
                    for key, value in ops.items():
                        if isinstance(value, dict):
                            # Nested structure like French operators
                            if first_digit == key:
                                for range_key, op_name in value.items():
                                    start, end = range_key.split("-")
                                    if start <= first_two <= end:
                                        result.operator_range = f"{op_name} ({first_two})"
                                        result.carrier_hint = op_name
                                        break
                        else:
                            # Simple mapping like German operators
                            if first_three == key or first_two == key:
                                result.operator_range = value
                                result.carrier_hint = value
                                break
            
            # Detect area/region
            if "areas" in patterns:
                for code, area in patterns["areas"].items():
                    if after_code.startswith(code):
                        result.operator_range = area
                        break
    
    # Special case: France detailed analysis
    if result.country_code == "+33":
        after = phone[3:].strip().replace(" ", "")
        if after.startswith("6"):
            result.phone_type = "mobile"
            prefix2 = after[:2] if len(after) >= 2 else "6"
            if prefix2 in ["60", "61", "62", "63"]:
                result.carrier_hint = "Orange (historic 06 range)"
            elif prefix2 in ["64", "65"]:
                result.carrier_hint = "SFR (historic 06 range)"
            elif prefix2 in ["66", "67"]:
                result.carrier_hint = "Bouygues Telecom"
            elif prefix2 in ["68", "69"]:
                result.carrier_hint = "Mixed operators"
            result.operator_range = f"06 range ({prefix2})"
        elif after.startswith("7"):
            result.phone_type = "mobile"
            prefix2 = after[:2] if len(after) >= 2 else "7"
            if prefix2 in ["70", "71", "72", "73"]:
                result.carrier_hint = "Free Mobile / MVNOs"
            else:
                result.carrier_hint = "New allocations (07)"
            result.operator_range = f"07 range ({prefix2})"
        elif after.startswith("1"):
            result.phone_type = "landline"
            result.operator_range = "Île-de-France (01)"
        elif after.startswith("2"):
            result.phone_type = "landline"
            result.operator_range = "Nord-Ouest (02)"
        elif after.startswith("3"):
            result.phone_type = "landline"
            result.operator_range = "Nord-Est (03)"
        elif after.startswith("4"):
            result.phone_type = "landline"
            result.operator_range = "Sud-Est (04)"
        elif after.startswith("5"):
            result.phone_type = "landline"
            result.operator_range = "Sud-Ouest (05)"
    
    return result


def analyze_email(email: Optional[str]) -> EmailAnalysis:
    """Enhanced email analysis with provider detection, security level, and domain type."""
    result = EmailAnalysis(raw=email)
    if not email or "@" not in email:
        return result
    
    email_lower = email.strip().lower()
    user_part, domain_part = email_lower.split("@", 1)
    
    # Analyze username
    if user_part:
        if user_part[0] != '*':
            result.username_first_char = user_part[0]
        if user_part[-1] != '*':
            result.username_last_char = user_part[-1]
        
        # Improved length estimation
        visible = sum(1 for c in user_part if c != '*')
        # Instagram typically uses *** for 3-6 hidden chars
        asterisk_groups = len(re.findall(r'\*{3,}', user_part))
        single_stars = user_part.count('*') - (asterisk_groups * 3)
        
        min_len = visible + single_stars + (asterisk_groups * 3)
        max_len = visible + single_stars + (asterisk_groups * 6)
        
        if visible >= 2:
            result.username_length_estimate = f"{min_len}-{max_len} chars (pattern: {len(user_part)})"
        else:
            result.username_length_estimate = f"{min_len}-{max_len} chars"
    
    # Analyze domain
    if domain_part:
        # Extract TLD
        if "." in domain_part:
            result.domain_tld = "." + domain_part.split(".")[-1]
            
            # Detect domain type from TLD
            tld = result.domain_tld.lower()
            if tld in [".edu", ".ac.uk", ".edu.au", ".edu.fr"]:
                result.domain_type = "educational"
            elif tld in [".gov", ".gouv.fr", ".gov.uk", ".mil"]:
                result.domain_type = "government"
            elif tld in [".org"]:
                result.domain_type = "organization"
            else:
                result.domain_type = "standard"
        
        # Try exact provider match
        for domain, (name, company, security, dtype) in EMAIL_PROVIDERS.items():
            if _match_obfuscated(domain_part, domain):
                result.provider = name
                result.provider_confidence = "high"
                result.security_level = security
                if dtype != "free":
                    result.domain_type = dtype
                result.score_modifier = 2 if security in ["high", "very_high"] else 0
                break
        
        # Fuzzy provider matching if no exact match
        if not result.provider:
            result.provider = _guess_email_provider(domain_part)
            if result.provider:
                result.provider_confidence = "medium"
                result.score_modifier = 1
            else:
                # Check if it looks like a business domain
                if result.domain_type == "standard" and not any(x in domain_part for x in ["mail", "email", "***"]):
                    result.domain_type = "business/custom"
                    result.provider = "Custom domain"
                    result.provider_confidence = "low"
        
        # Set security level if not set
        if not result.security_level:
            if result.domain_type in ["educational", "government"]:
                result.security_level = "medium"
            elif result.provider and "proton" in result.provider.lower():
                result.security_level = "very_high"
            elif result.domain_type == "business/custom":
                result.security_level = "medium"
    
    return result


def _match_obfuscated(text: str, pattern: str) -> bool:
    """Check if obfuscated text could match a pattern."""
    text, pattern = text.lower(), pattern.lower()
    if abs(len(text) - len(pattern)) > 3:
        return False
    
    # Direct comparison where possible
    matches = 0
    for i, (t, p) in enumerate(zip(text, pattern)):
        if t == '*' or p == '*':
            continue
        if t == p:
            matches += 1
        else:
            return False
    
    return matches >= min(3, len(pattern) - pattern.count('*'))


def _guess_email_provider(domain: str) -> Optional[str]:
    """Guess email provider from partial/obfuscated domain."""
    d = domain.lower()
    
    # Check visible parts
    guesses = [
        (["g***l.com", "gm**l.com", "g****.com"], "Gmail"),
        (["y***o.com", "ya**o.com", "y****.com"], "Yahoo"),
        (["o*****k.com", "ou*****.com"], "Outlook"),
        (["h*****l.com", "ho****l.com"], "Hotmail"),
        (["i****d.com", "ic***d.com"], "iCloud"),
        (["p*****mail.com", "pr****mail.com"], "ProtonMail"),
    ]
    
    for patterns, provider in guesses:
        for p in patterns:
            if _match_obfuscated(d, p):
                return f"{provider} (likely)"
    
    # Fallback to first letter + TLD matching
    if d.startswith("g") and ".com" in d and len(d) <= 12:
        return "Gmail (likely)"
    if d.startswith("y") and ".com" in d:
        return "Yahoo (likely)"
    if d.startswith("o") and ".com" in d and len(d) > 8:
        return "Outlook (likely)"
    if d.startswith("h") and ".com" in d and len(d) > 8:
        return "Hotmail (likely)"
    if d.startswith("i") and ".com" in d:
        return "iCloud (likely)"
    
    # French providers
    if ".fr" in d:
        if d.startswith("o"): return "Orange (likely)"
        if d.startswith("f"): return "Free (likely)"
        if d.startswith("s"): return "SFR (likely)"
        if d.startswith("l"): return "LaPoste (likely)"
        if d.startswith("w"): return "Wanadoo (likely)"
    
    # German providers
    if ".de" in d:
        if d.startswith("g"): return "GMX (likely)"
        if d.startswith("w"): return "Web.de (likely)"
        if d.startswith("t"): return "T-Online (likely)"
    
    return None


def analyze_bio(bio: str) -> BioAnalysis:
    """Analyze bio for contact info and scam indicators."""
    result = BioAnalysis()
    if not bio:
        return result
    
    result.emails = re.findall(r"[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}", bio)
    phones = re.findall(r"(\+?\d{1,3}[-.\s]?)?(\(?\d{2,4}\)?[-.\s]?)?\d{2,4}[-.\s]?\d{2,4}[-.\s]?\d{2,4}", bio)
    result.phones = [''.join(p).strip() for p in phones if len(re.sub(r'[^0-9]', '', ''.join(p))) >= 8]
    result.urls = re.findall(r"(https?://[^\s]+|www\.[^\s]+)", bio)
    
    scam_score = 0
    for pattern, indicator, points in SCAM_PATTERNS:
        if re.search(pattern, bio, re.IGNORECASE):
            result.scam_indicators.append(indicator)
            scam_score += points
    
    for url in result.urls:
        for sus in SUSPICIOUS_URLS:
            if sus in url.lower():
                result.scam_indicators.append(f"Shortened URL ({sus})")
                scam_score += 5
                break
    
    if scam_score >= 40: result.risk_level = "high"
    elif scam_score >= 20: result.risk_level = "moderate"
    elif scam_score > 0: result.risk_level = "low"
    
    return result


def get_reverse_image_urls(url: str) -> List[Tuple[str, str]]:
    enc = quote(url, safe='')
    return [
        ("Google Lens", f"https://lens.google.com/uploadbyurl?url={enc}"),
        ("Yandex", f"https://yandex.com/images/search?rpt=imageview&url={enc}"),
        ("TinEye", f"https://tineye.com/search?url={enc}"),
        ("Bing", f"https://www.bing.com/images/search?view=detailv2&iss=sbi&q=imgurl:{enc}"),
    ]


# ══════════════════════════════════════════════════════════════════════════════
# INSTAGRAM CLIENT
# ══════════════════════════════════════════════════════════════════════════════

class InstagramClient:
    WEB_API = "https://www.instagram.com/api/v1/users/web_profile_info/"
    LOOKUP_API = "https://i.instagram.com/api/v1/users/lookup/"
    USER_INFO_API = "https://i.instagram.com/api/v1/users/{user_id}/info/"
    SEARCH_API = "https://i.instagram.com/api/v1/users/search/"
    
    IG_APP_ID = "936619743392459"
    WEB_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36"
    MOBILE_UA = "Instagram 275.0.0.27.98 Android"
    TIMEOUT = 20

    def __init__(self):
        self.session = requests.Session()

    def get_profile(self, username: str) -> Optional[dict]:
        """Fetch profile via web API."""
        headers = {
            "User-Agent": self.WEB_UA, "x-ig-app-id": self.IG_APP_ID,
            "x-requested-with": "XMLHttpRequest", "Accept": "*/*",
            "Referer": f"https://www.instagram.com/{username}/",
        }
        try:
            r = self.session.get(self.WEB_API, headers=headers, 
                                 params={"username": username}, timeout=self.TIMEOUT)
            if r.status_code == 200: 
                return r.json()
            elif r.status_code == 404: 
                print(f"   {Colors.RED}[!] User not found{Colors.RESET}")
            elif r.status_code == 401: 
                print(f"   {Colors.RED}[!] Auth required{Colors.RESET}")
            elif r.status_code == 429: 
                print(f"   {Colors.RED}[!] Rate limited{Colors.RESET}")
            else: 
                print(f"   {Colors.RED}[!] HTTP {r.status_code}{Colors.RESET}")
        except Exception as e:
            print(f"   {Colors.RED}[!] Error: {e}{Colors.RESET}")
        return None

    def get_obfuscated(self, username: str) -> Tuple[Optional[str], Optional[str]]:
        """Fetch obfuscated email/phone via mobile lookup API."""
        data = {
            "ig_sig_key_version": "4", 
            "signed_body": f'SIGNATURE.{json.dumps({"q": username, "ig_sig_key_version": "4"})}'
        }
        try:
            r = self.session.post(self.LOOKUP_API, 
                                  headers={"User-Agent": self.MOBILE_UA}, 
                                  data=data, verify=False, timeout=self.TIMEOUT)
            if r.status_code == 200:
                j = r.json()
                return j.get("obfuscated_email"), j.get("obfuscated_phone")
        except: 
            pass
        return None, None

    def get_additional_info(self, username: str, user_id: Optional[str]) -> Optional[AdditionalInfo]:
        """Try additional API endpoints for more info."""
        result = AdditionalInfo()
        
        # Try user info endpoint
        if user_id:
            try:
                url = self.USER_INFO_API.format(user_id=user_id)
                r = self.session.get(url, headers={"User-Agent": self.MOBILE_UA}, timeout=self.TIMEOUT)
                if r.status_code == 200:
                    j = r.json()
                    user = j.get("user", {})
                    result.public_email = user.get("public_email")
                    result.public_phone = user.get("public_phone_number")
                    result.phone_country_code = user.get("public_phone_country_code")
                    result.city_name = user.get("city_name")
                    result.account_type = user.get("account_type")
            except:
                pass
        
        # Try search endpoint for account type
        try:
            r = self.session.get(self.SEARCH_API, 
                                 headers={"User-Agent": self.MOBILE_UA},
                                 params={"q": username}, timeout=self.TIMEOUT)
            if r.status_code == 200:
                j = r.json()
                for user in j.get("users", []):
                    if user.get("username") == username:
                        if not result.account_type:
                            result.account_type = user.get("account_type")
                        break
        except:
            pass
        
        if result.public_email or result.public_phone or result.city_name or result.account_type:
            return result
        return None

    def get_wayback(self, username: str) -> Optional[Tuple[str, str]]:
        """Get first Wayback Machine archive."""
        try:
            r = self.session.get(
                f"https://web.archive.org/cdx/search/cdx?url=instagram.com/{username}&output=json&limit=1&from=2010",
                timeout=15
            )
            if r.status_code == 200:
                data = r.json()
                if len(data) > 1:
                    ts = data[1][1]
                    if ts and len(ts) >= 8:
                        return f"{ts[:4]}-{ts[4:6]}-{ts[6:8]}", f"https://web.archive.org/web/{ts}/https://instagram.com/{username}"
        except:
            pass
        return None

    def parse(self, data: dict, username: str) -> Optional[InstagramProfile]:
        """Parse API response into InstagramProfile."""
        try:
            u = data.get("data", {}).get("user")
            if not u:
                return None
            
            p = InstagramProfile(username=username)
            p.user_id = u.get("id")
            p.full_name = u.get("full_name")
            p.biography = u.get("biography")
            p.external_url = u.get("external_url")
            p.profile_pic_url = u.get("profile_pic_url_hd") or u.get("profile_pic_url")
            p.is_private = u.get("is_private", False)
            p.is_verified = u.get("is_verified", False)
            p.followers = u.get("edge_followed_by", {}).get("count")
            p.following = u.get("edge_follow", {}).get("count")
            p.posts_count = u.get("edge_owner_to_timeline_media", {}).get("count")
            p.is_business = u.get("is_business_account", False)
            p.business_category = u.get("category_name")
            p.business_email = u.get("business_email")
            p.business_phone = u.get("business_phone_number")
            p.profile_pic_id = u.get("profile_pic_id")
            p.has_highlight_reels = (u.get("edge_highlight_reels", {}).get("count", 0) or 0) > 0
            p.has_videos = (u.get("edge_felix_video_timeline", {}).get("count", 0) or 0) > 0
            p.has_clips = (u.get("edge_clips_viewer", {}).get("count", 0) or 0) > 0
            p.has_channel = u.get("has_channel", False)
            p.has_guides = u.get("has_guides", False)
            p.is_professional_account = u.get("is_professional_account", False)
            p.is_supervision_enabled = u.get("is_supervision_enabled", False)
            p.pronouns = u.get("pronouns", []) or []
            p.is_memorialized = u.get("is_memorialized", False)
            
            bio_links = u.get("bio_links", [])
            if bio_links:
                p.bio_links = [l.get("url") for l in bio_links if isinstance(l, dict) and l.get("url")]
            
            if not p.is_private:
                edges = u.get("edge_owner_to_timeline_media", {}).get("edges", [])
                if edges:
                    timestamps = [e.get("node", {}).get("taken_at_timestamp") for e in edges]
                    timestamps = [t for t in timestamps if t]
                    if timestamps:
                        oldest = min(timestamps)
                        dt = datetime.fromtimestamp(oldest)
                        p.first_post_date = dt.strftime("%Y-%m-%d %H:%M:%S")
                        p.estimated_creation = f"~{dt.strftime('%B %Y')}"
            
            return p
        except Exception as e:
            print(f"   {Colors.RED}[!] Parse error: {e}{Colors.RESET}")
            return None


# ══════════════════════════════════════════════════════════════════════════════
# TRUST SCORE
# ══════════════════════════════════════════════════════════════════════════════

def fmt(n: int) -> str:
    if n >= 1000000: return f"{n/1000000:.1f}M"
    if n >= 1000: return f"{n/1000:.1f}K"
    return str(n)


def calc_trust(p: InstagramProfile) -> Tuple[int, List[Tuple[str, int, str]]]:
    score, details = 10, [("✓ Account exists", 10, "green")]
    
    if p.is_verified: score += 30; details.append(("✓ Verified", 30, "green"))
    if p.is_memorialized: score += 20; details.append(("✓ Memorialized", 20, "green"))
    
    if p.wayback_first_archive:
        try:
            yrs = datetime.now().year - int(p.wayback_first_archive[:4])
            if yrs >= 5: score += 25; details.append((f"✓ Since {p.wayback_first_archive[:4]} ({yrs}+ yrs)", 25, "green"))
            elif yrs >= 3: score += 20; details.append((f"✓ Since {p.wayback_first_archive[:4]}", 20, "green"))
            elif yrs >= 1: score += 12; details.append((f"~ Since {p.wayback_first_archive[:4]}", 12, "yellow"))
            else: score += 5; details.append(("~ Recent account", 5, "yellow"))
        except: pass
    
    f, g = p.followers or 0, p.following or 0
    if f >= 10000: score += 15; details.append((f"✓ Large following ({fmt(f)})", 15, "green"))
    elif f >= 1000: score += 12; details.append((f"✓ Good following ({fmt(f)})", 12, "green"))
    elif f >= 100: score += 8; details.append((f"~ Moderate following", 8, "yellow"))
    elif f > 0: score += 4; details.append((f"~ Small following", 4, "yellow"))
    
    if f > 0 and g > 0:
        r = f / g
        if r >= 10: score += 10; details.append((f"✓ Influencer ratio", 10, "green"))
        elif r >= 1: score += 8; details.append((f"✓ Healthy ratio", 8, "green"))
        elif r < 0.1 and g > 500: score -= 5; details.append(("⚠ Suspicious ratio", -5, "red"))
    
    posts = p.posts_count or 0
    if posts >= 50: score += 8; details.append((f"✓ Active ({posts} posts)", 8, "green"))
    elif posts >= 10: score += 5; details.append((f"~ Some posts", 5, "yellow"))
    elif posts == 0 and not p.is_private: score -= 3; details.append(("⚠ No posts", -3, "red"))
    
    if p.biography and len(p.biography) > 50: score += 5; details.append(("✓ Detailed bio", 5, "green"))
    if p.full_name and " " in p.full_name: score += 5; details.append(("✓ Real name format", 5, "green"))
    if p.profile_pic_id: score += 5; details.append(("✓ Custom profile pic", 5, "green"))
    if p.bio_links: score += 5; details.append(("✓ Bio links", 5, "green"))
    
    if p.email_analysis and p.email_analysis.raw:
        score += p.email_analysis.score_modifier
        if p.email_analysis.provider:
            details.append((f"✓ Email: {p.email_analysis.provider}", p.email_analysis.score_modifier, "green"))
    
    if p.phone_analysis and p.phone_analysis.raw:
        score += p.phone_analysis.score_modifier
        if p.phone_analysis.country:
            color = "red" if p.phone_analysis.risk_level in ["very_high", "high"] else "yellow" if p.phone_analysis.risk_level == "moderate" else "green"
            details.append((f"{'🚩' if color == 'red' else '✓'} Phone: {p.phone_analysis.country}", p.phone_analysis.score_modifier, color))
    
    if p.bio_analysis:
        if p.bio_analysis.risk_level == "high": score -= 15; details.append(("🚩 Bio: HIGH RISK", -15, "red"))
        elif p.bio_analysis.risk_level == "moderate": score -= 8; details.append(("⚠ Bio: Moderate risk", -8, "yellow"))
    
    if p.has_highlight_reels: score += 5; details.append(("✓ Story highlights", 5, "green"))
    if p.pronouns: score += 4; details.append(("✓ Pronouns set", 4, "green"))
    if p.is_private: score += 5; details.append(("○ Private account", 5, "green"))
    
    return max(0, min(100, score)), details


# ══════════════════════════════════════════════════════════════════════════════
# DISPLAY
# ══════════════════════════════════════════════════════════════════════════════

def display(p: InstagramProfile):
    print(f"\n {Colors.GREEN}[✓] Profile:{Colors.RESET} https://instagram.com/{p.username}")
    st = [f"{Colors.YELLOW}PRIVATE{Colors.RESET}" if p.is_private else f"{Colors.GREEN}PUBLIC{Colors.RESET}"]
    if p.is_verified: st.append(f"{Colors.BLUE}VERIFIED{Colors.RESET}")
    if p.is_business: st.append(f"{Colors.CYAN}BUSINESS{Colors.RESET}")
    print(f"   └ Status: {' | '.join(st)}")
    
    print(f"\n {Colors.BOLD}[PROFILE]{Colors.RESET}")
    if p.user_id: print(f"   ├ ID: {p.user_id}")
    if p.full_name: print(f"   ├ Name: {p.full_name}")
    if p.biography: print(f"   ├ Bio: {' '.join(p.biography.splitlines())[:80]}...")
    if p.external_url: print(f"   └ URL: {p.external_url}")
    
    print(f"\n {Colors.BOLD}[STATS]{Colors.RESET}")
    print(f"   ├ Followers: {fmt(p.followers) if p.followers else 'N/A'}")
    print(f"   ├ Following: {fmt(p.following) if p.following else 'N/A'}")
    print(f"   └ Posts: {p.posts_count if p.posts_count else 'N/A'}")
    
    # Enhanced obfuscated info display
    print(f"\n {Colors.BOLD}[OBFUSCATED INFO]{Colors.RESET}")
    
    # Additional info from extra APIs
    if p.additional_info:
        ai = p.additional_info
        if ai.city_name:
            print(f"   ├ 📍 Location: {Colors.GREEN}{ai.city_name}{Colors.RESET}")
        if ai.public_email:
            print(f"   ├ 📧 Public Email: {Colors.GREEN}{ai.public_email}{Colors.RESET}")
        if ai.public_phone:
            print(f"   ├ 📱 Public Phone: {Colors.GREEN}{ai.public_phone}{Colors.RESET}")
        if ai.account_type:
            types = {1: "Personal", 2: "Business", 3: "Creator"}
            print(f"   ├ Account Type: {types.get(ai.account_type, 'Unknown')}")
        print(f"   ├ {'─' * 35}")
    
    # Email analysis
    if p.email_analysis and p.email_analysis.raw:
        ea = p.email_analysis
        print(f"   ├ Email: {ea.raw}")
        if ea.provider:
            conf = {"high": "✓", "medium": "~", "low": "?"}.get(ea.provider_confidence, "")
            print(f"   │  ├ Provider: {conf} {ea.provider}")
        if ea.domain_type and ea.domain_type != "standard":
            emoji = {"educational": "🎓", "government": "🏛", "organization": "🏢", "business/custom": "💼"}.get(ea.domain_type, "")
            print(f"   │  ├ Domain: {emoji} {ea.domain_type.upper()}")
        if ea.security_level:
            sec = {"very_high": "🔒 VERY HIGH", "high": "🔒 HIGH", "medium": "🔓 MEDIUM", "low": "⚠ LOW"}.get(ea.security_level, "")
            print(f"   │  ├ Security: {sec}")
        if ea.username_first_char or ea.username_last_char:
            hints = []
            if ea.username_first_char: hints.append(f"starts '{ea.username_first_char}'")
            if ea.username_last_char: hints.append(f"ends '{ea.username_last_char}'")
            print(f"   │  ├ Username: {', '.join(hints)}")
        if ea.username_length_estimate:
            print(f"   │  ├ Length: {ea.username_length_estimate}")
        if ea.domain_tld:
            print(f"   │  └ TLD: {ea.domain_tld}")
    else:
        print(f"   ├ Email: N/A")
    
    # Phone analysis
    print(f"   │")
    if p.phone_analysis and p.phone_analysis.raw:
        pa = p.phone_analysis
        print(f"   ├ Phone: {pa.raw}")
        if pa.country:
            risk_emoji = {"very_high": "🚩", "high": "🚩", "moderate": "⚠", "trusted": "✓"}.get(pa.risk_level, "○")
            risk_color = {"very_high": Colors.RED, "high": Colors.RED, "moderate": Colors.YELLOW, "trusted": Colors.GREEN}.get(pa.risk_level, "")
            print(f"   │  ├ Country: {risk_color}{risk_emoji} {pa.country} ({pa.country_code}){Colors.RESET}")
            if pa.risk_level in ["very_high", "high"]:
                warning = "⚠ SCAM HOTSPOT - Brouteurs region" if pa.risk_level == "very_high" else "⚠ Known for scams"
                print(f"   │  ├ {Colors.RED}Warning: {warning}{Colors.RESET}")
        if pa.phone_type:
            emoji = {"mobile": "📱", "landline": "☎"}.get(pa.phone_type, "📞")
            print(f"   │  ├ Type: {emoji} {pa.phone_type.capitalize()}")
        if pa.phone_format:
            print(f"   │  ├ Format: {pa.phone_format}")
        if pa.operator_range:
            print(f"   │  ├ Range: {pa.operator_range}")
        if pa.carrier_hint:
            print(f"   │  ├ Carrier: {pa.carrier_hint}")
        if pa.visible_digits and len(pa.visible_digits) > 3:
            print(f"   │  └ Visible: {pa.visible_digits}")
    else:
        print(f"   └ Phone: N/A")
    
    # Bio analysis
    if p.bio_analysis and (p.bio_analysis.scam_indicators or p.bio_analysis.emails or p.bio_analysis.phones):
        print(f"\n {Colors.BOLD}[BIO ANALYSIS]{Colors.RESET}")
        if p.bio_analysis.risk_level:
            rc = {"high": Colors.RED, "moderate": Colors.YELLOW, "low": Colors.YELLOW}.get(p.bio_analysis.risk_level, "")
            rt = {"high": "🚩 HIGH RISK", "moderate": "⚠ MODERATE", "low": "○ Low"}.get(p.bio_analysis.risk_level, "")
            print(f"   ├ Risk: {rc}{rt}{Colors.RESET}")
        if p.bio_analysis.scam_indicators:
            print(f"   ├ {Colors.RED}Scam indicators:{Colors.RESET}")
            for i in p.bio_analysis.scam_indicators[:5]:
                print(f"   │  • {i}")
        for e in p.bio_analysis.emails:
            print(f"   ├ 📧 {Colors.GREEN}{e}{Colors.RESET}")
        for ph in p.bio_analysis.phones:
            print(f"   ├ 📱 {Colors.GREEN}{ph}{Colors.RESET}")
    
    # Account age
    print(f"\n {Colors.BOLD}[ACCOUNT AGE]{Colors.RESET}")
    if p.wayback_first_archive:
        print(f"   ├ Wayback: {Colors.GREEN}{p.wayback_first_archive}{Colors.RESET}")
        print(f"   └ Archive: {p.wayback_url}")
    elif p.first_post_date:
        print(f"   ├ First post: {p.first_post_date}")
        print(f"   └ Estimated: {p.estimated_creation}")
    else:
        print(f"   └ {Colors.YELLOW}Unknown{Colors.RESET}")
    
    # Trust score
    print(f"\n {Colors.BOLD}[TRUST SCORE]{Colors.RESET}")
    sc = p.trust_score
    col = Colors.GREEN if sc >= 60 else Colors.YELLOW if sc >= 35 else Colors.RED
    verd = "HIGHLY TRUSTWORTHY" if sc >= 75 else "LIKELY LEGITIMATE" if sc >= 60 else "MODERATE" if sc >= 45 else "LOW CONFIDENCE" if sc >= 35 else "SUSPICIOUS" if sc >= 20 else "HIGH RISK"
    bar = "█" * (sc // 5) + "░" * (20 - sc // 5)
    print(f"   ┌{'─'*22}┐")
    print(f"   │ {col}{bar}{Colors.RESET} │ {col}{sc}/100{Colors.RESET}")
    print(f"   └{'─'*22}┘")
    print(f"   {Colors.BOLD}Verdict: {col}{verd}{Colors.RESET}")
    print(f"\n   Details:")
    for d, pts, c in p.trust_details:
        cc = {"green": Colors.GREEN, "yellow": Colors.YELLOW, "red": Colors.RED}.get(c, "")
        print(f"   {cc}  {d} ({'+' if pts > 0 else ''}{pts}){Colors.RESET}")
    
    # Reverse image search
    if p.profile_pic_url:
        print(f"\n {Colors.BOLD}[REVERSE IMAGE SEARCH]{Colors.RESET}")
        for n, u in get_reverse_image_urls(p.profile_pic_url):
            print(f"   • {n}: {u[:60]}...")
    
    # Cross-platform
    print(f"\n {Colors.BOLD}[CROSS-PLATFORM]{Colors.RESET}")
    for n, u in CROSS_PLATFORM_URLS[:6]:
        print(f"   • {n}: {u.format(u=p.username)}")
    print(f"   ... +{len(CROSS_PLATFORM_URLS)-6} more")


# ══════════════════════════════════════════════════════════════════════════════
# MAIN
# ══════════════════════════════════════════════════════════════════════════════

def lookup(username: str, verbose: bool = True) -> Optional[InstagramProfile]:
    client = InstagramClient()
    if verbose:
        print(f"\n {Colors.CYAN}[*] Looking up: {username}{Colors.RESET}")
        print("─" * 60)
        print(f" {Colors.CYAN}[*] Fetching profile...{Colors.RESET}")
    
    data = client.get_profile(username)
    if not data:
        return None
    
    p = client.parse(data, username)
    if not p:
        return None
    
    if verbose: print(f" {Colors.CYAN}[*] Fetching obfuscated info...{Colors.RESET}")
    time.sleep(0.5)
    p.obfuscated_email, p.obfuscated_phone = client.get_obfuscated(username)
    
    # Get additional info from extra APIs
    if verbose: print(f" {Colors.CYAN}[*] Checking additional APIs...{Colors.RESET}")
    p.additional_info = client.get_additional_info(username, p.user_id)
    
    # Analyze email and phone
    p.email_analysis = analyze_email(p.obfuscated_email)
    p.phone_analysis = analyze_phone(p.obfuscated_phone)
    
    if verbose: print(f" {Colors.CYAN}[*] Analyzing bio...{Colors.RESET}")
    if p.biography:
        p.bio_analysis = analyze_bio(p.biography)
    
    if verbose: print(f" {Colors.CYAN}[*] Checking Wayback...{Colors.RESET}")
    wb = client.get_wayback(username)
    if wb:
        p.wayback_first_archive, p.wayback_url = wb
    
    if verbose: print(f" {Colors.CYAN}[*] Calculating trust score...{Colors.RESET}")
    p.trust_score, p.trust_details = calc_trust(p)
    
    if verbose:
        display(p)
    return p


def export_json(p: InstagramProfile) -> str:
    return json.dumps({
        "username": p.username, "user_id": p.user_id, "full_name": p.full_name,
        "biography": p.biography, "followers": p.followers, "following": p.following,
        "posts_count": p.posts_count, "is_private": p.is_private, "is_verified": p.is_verified,
        "obfuscated_email": p.obfuscated_email, "obfuscated_phone": p.obfuscated_phone,
        "email_analysis": {
            "provider": p.email_analysis.provider if p.email_analysis else None,
            "confidence": p.email_analysis.provider_confidence if p.email_analysis else None,
            "domain_type": p.email_analysis.domain_type if p.email_analysis else None,
            "security_level": p.email_analysis.security_level if p.email_analysis else None,
            "username_length": p.email_analysis.username_length_estimate if p.email_analysis else None,
        } if p.email_analysis else None,
        "phone_analysis": {
            "country": p.phone_analysis.country if p.phone_analysis else None,
            "country_code": p.phone_analysis.country_code if p.phone_analysis else None,
            "risk_level": p.phone_analysis.risk_level if p.phone_analysis else None,
            "phone_type": p.phone_analysis.phone_type if p.phone_analysis else None,
            "carrier": p.phone_analysis.carrier_hint if p.phone_analysis else None,
            "operator_range": p.phone_analysis.operator_range if p.phone_analysis else None,
        } if p.phone_analysis else None,
        "additional_info": {
            "city": p.additional_info.city_name if p.additional_info else None,
            "public_email": p.additional_info.public_email if p.additional_info else None,
            "public_phone": p.additional_info.public_phone if p.additional_info else None,
            "account_type": p.additional_info.account_type if p.additional_info else None,
        } if p.additional_info else None,
        "bio_analysis": {
            "risk": p.bio_analysis.risk_level if p.bio_analysis else None,
            "scam_indicators": p.bio_analysis.scam_indicators if p.bio_analysis else [],
            "emails": p.bio_analysis.emails if p.bio_analysis else [],
            "phones": p.bio_analysis.phones if p.bio_analysis else [],
        } if p.bio_analysis else None,
        "wayback_date": p.wayback_first_archive,
        "trust_score": p.trust_score,
        "trust_details": [{"text": t, "points": pts} for t, pts, c in p.trust_details],
        "reverse_image_urls": get_reverse_image_urls(p.profile_pic_url) if p.profile_pic_url else [],
        "timestamp": datetime.now().isoformat(),
    }, indent=2, ensure_ascii=False)


def main():
    parser = argparse.ArgumentParser(description="WhoIG - Instagram OSINT Tool")
    parser.add_argument("username", help="Instagram username")
    parser.add_argument("--json", action="store_true", help="JSON output")
    parser.add_argument("-o", "--output", help="Save to file")
    parser.add_argument("-q", "--quiet", action="store_true", help="Quiet mode")
    args = parser.parse_args()
    
    username = args.username.lstrip("@").strip()
    p = lookup(username, verbose=not args.quiet and not args.json)
    
    print("─" * 60)
    if p:
        if args.json or args.output:
            j = export_json(p)
            if args.output:
                with open(args.output, 'w') as f:
                    f.write(j)
                print(f" {Colors.GREEN}[✓] Saved to {args.output}{Colors.RESET}")
            else:
                print(j)
        else:
            print(f" {Colors.GREEN}[✓] Complete{Colors.RESET}")
    else:
        print(f" {Colors.RED}[!] Failed{Colors.RESET}")
        sys.exit(1)


if __name__ == "__main__":
    main()
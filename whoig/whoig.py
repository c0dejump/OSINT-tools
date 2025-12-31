#!/usr/bin/env python3
"""Instagram OSINT lookup tool."""

import argparse
import json
import sys
import time
from dataclasses import dataclass
from datetime import datetime
from typing import Optional, Any

import requests

requests.packages.urllib3.disable_warnings(requests.packages.urllib3.exceptions.InsecureRequestWarning)


@dataclass
class InstagramProfile:
    """Instagram profile data."""
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
    first_post_date: Optional[str] = None
    estimated_creation: Optional[str] = None
    wayback_first_archive: Optional[str] = None
    wayback_url: Optional[str] = None
    trust_score: int = 0
    trust_details: list = None
    
    def __post_init__(self):
        if self.trust_details is None:
            self.trust_details = []


class Colors:
    GREEN = "\033[32m"
    YELLOW = "\033[33m"
    BLUE = "\033[34m"
    CYAN = "\033[36m"
    RED = "\033[31m"
    RESET = "\033[0m"
    BOLD = "\033[1m"


def print_separator():
    print("─" * 60)


class InstagramLookup:
    """Instagram profile lookup using internal APIs."""

    # Instagram web API
    WEB_PROFILE_API = "https://www.instagram.com/api/v1/users/web_profile_info/"
    
    # Instagram GraphQL API for pagination
    GRAPHQL_API = "https://www.instagram.com/graphql/query/"
    
    # Instagram mobile API for obfuscated info
    LOOKUP_API = "https://i.instagram.com/api/v1/users/lookup/"
    
    # App ID required for web API
    IG_APP_ID = "936619743392459"
    
    # Query hash for user media
    MEDIA_QUERY_HASH = "e769aa130647d2571c0dc298b6f4a74c"
    
    WEB_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    MOBILE_USER_AGENT = "Instagram 101.0.0.15.120"
    
    TIMEOUT = 20

    def __init__(self):
        self.session = requests.Session()

    def calculate_trust_score(self, profile: InstagramProfile) -> tuple[int, list]:
        """
        Calculate trust score based on multiple factors.
        Returns (score 0-100, list of details)
        """
        score = 0
        details = []
        
        # 1. Verified account (+25 points)
        if profile.is_verified:
            score += 25
            details.append(("✓ Verified account", 25, "green"))
        
        # 2. Account age via Wayback (+20 points max)
        if profile.wayback_first_archive:
            try:
                archive_year = int(profile.wayback_first_archive.split("-")[0])
                current_year = datetime.now().year
                years_old = current_year - archive_year
                if years_old >= 5:
                    score += 20
                    details.append((f"✓ Account archived {years_old}+ years ago", 20, "green"))
                elif years_old >= 3:
                    score += 15
                    details.append((f"✓ Account archived {years_old} years ago", 15, "green"))
                elif years_old >= 1:
                    score += 10
                    details.append((f"~ Account archived {years_old} year(s) ago", 10, "yellow"))
                else:
                    score += 5
                    details.append(("~ Account archived recently", 5, "yellow"))
            except:
                pass
        else:
            details.append(("✗ No Wayback archive found", 0, "red"))
        
        # 3. Followers/Following ratio (+15 points max)
        if profile.followers and profile.following:
            if profile.following > 0:
                ratio = profile.followers / profile.following
                if ratio >= 1.0 and profile.followers >= 500:
                    score += 15
                    details.append((f"✓ Healthy follower ratio ({ratio:.1f}:1)", 15, "green"))
                elif ratio >= 0.5:
                    score += 10
                    details.append((f"~ Acceptable follower ratio ({ratio:.1f}:1)", 10, "yellow"))
                elif ratio < 0.1 and profile.following > 1000:
                    details.append((f"✗ Suspicious ratio - follows many, few followers ({ratio:.2f}:1)", 0, "red"))
                else:
                    score += 5
                    details.append((f"~ Low follower ratio ({ratio:.2f}:1)", 5, "yellow"))
            elif profile.followers > 100:
                score += 10
                details.append(("~ Follows nobody but has followers", 10, "yellow"))
        
        # 4. Post count (+10 points max)
        if profile.posts_count:
            if profile.posts_count >= 50:
                score += 10
                details.append((f"✓ Active poster ({profile.posts_count} posts)", 10, "green"))
            elif profile.posts_count >= 10:
                score += 7
                details.append((f"~ Moderate activity ({profile.posts_count} posts)", 7, "yellow"))
            elif profile.posts_count >= 1:
                score += 3
                details.append((f"~ Low activity ({profile.posts_count} posts)", 3, "yellow"))
            else:
                details.append(("✗ No posts", 0, "red"))
        else:
            details.append(("✗ No posts or private", 0, "red"))
        
        # 5. Has biography (+5 points)
        if profile.biography and len(profile.biography.strip()) > 10:
            score += 5
            details.append(("✓ Has biography", 5, "green"))
        else:
            details.append(("✗ No/short biography", 0, "red"))
        
        # 6. Has profile picture (+5 points)
        if profile.profile_pic_url and "default" not in profile.profile_pic_url.lower():
            score += 5
            details.append(("✓ Has profile picture", 5, "green"))
        else:
            details.append(("✗ Default/no profile picture", 0, "red"))
        
        # 7. Has external URL (+5 points)
        if profile.external_url:
            score += 5
            details.append(("✓ Has external website", 5, "green"))
        
        # 8. Business account with contact info (+10 points)
        if profile.is_business:
            if profile.business_email or profile.business_phone:
                score += 10
                details.append(("✓ Business account with contact info", 10, "green"))
            else:
                score += 5
                details.append(("~ Business account (no contact info)", 5, "yellow"))
        
        # 9. Has obfuscated contact info (+5 points)
        if profile.obfuscated_email or profile.obfuscated_phone:
            score += 5
            details.append(("✓ Has linked email/phone", 5, "green"))
        
        # 10. Full name matches username pattern (-5 to +5 points)
        if profile.full_name:
            # Check for random-looking names
            import re
            if re.match(r'^[A-Za-z]+ [A-Za-z]+$', profile.full_name):
                score += 5
                details.append(("✓ Real-looking name format", 5, "green"))
            elif re.match(r'^[a-z0-9_]+$', profile.full_name.lower()) and profile.full_name == profile.username:
                details.append(("~ Name same as username", 0, "yellow"))
        
        # 11. Suspicious patterns (-10 points each)
        if profile.followers and profile.followers > 10000 and profile.posts_count and profile.posts_count < 5:
            score -= 10
            details.append(("⚠ Suspicious: Many followers but few posts", -10, "red"))
        
        if profile.following and profile.following > 5000 and profile.followers and profile.followers < 100:
            score -= 10
            details.append(("⚠ Suspicious: Follows many but few followers", -10, "red"))
        
        # Normalize score to 0-100
        score = max(0, min(100, score))
        
        return score, details

    def get_wayback_first_archive(self, username: str) -> Optional[tuple[str, str]]:
        """Get the first archive date from Wayback Machine."""
        url = f"https://instagram.com/{username}"
        api_url = f"https://archive.org/wayback/available?url={url}&timestamp=19900101"
        
        try:
            resp = self.session.get(api_url, timeout=self.TIMEOUT)
            if resp.status_code == 200:
                data = resp.json()
                snapshots = data.get("archived_snapshots", {})
                closest = snapshots.get("closest", {})
                
                if closest and closest.get("available"):
                    timestamp = closest.get("timestamp", "")  # Format: YYYYMMDDHHmmss
                    archive_url = closest.get("url", "")
                    
                    if timestamp and len(timestamp) >= 8:
                        dt = datetime.strptime(timestamp[:8], "%Y%m%d")
                        return dt.strftime("%Y-%m-%d"), archive_url
        except Exception:
            pass
        
        # Try CDX API for more accurate first capture
        cdx_url = f"https://web.archive.org/cdx/search/cdx?url=instagram.com/{username}&output=json&limit=1&from=2010"
        
        try:
            resp = self.session.get(cdx_url, timeout=self.TIMEOUT)
            if resp.status_code == 200:
                data = resp.json()
                if len(data) > 1:  # First row is header
                    row = data[1]
                    timestamp = row[1]  # timestamp is second field
                    if timestamp and len(timestamp) >= 8:
                        dt = datetime.strptime(timestamp[:8], "%Y%m%d")
                        archive_url = f"https://web.archive.org/web/{timestamp}/https://instagram.com/{username}"
                        return dt.strftime("%Y-%m-%d"), archive_url
        except Exception:
            pass
        
        return None

    def get_oldest_post_date(self, user_id: str, posts_count: int) -> Optional[tuple[str, str]]:
        """Try to get the oldest post date by paginating through posts."""
        if not user_id or not posts_count or posts_count == 0:
            return None
            
        headers = {
            "User-Agent": self.WEB_USER_AGENT,
            "x-ig-app-id": self.IG_APP_ID,
            "x-requested-with": "XMLHttpRequest",
        }
        
        oldest_timestamp = None
        end_cursor = None
        has_next = True
        max_iterations = 50  # Limit to avoid infinite loops
        iteration = 0
        
        while has_next and iteration < max_iterations:
            iteration += 1
            
            variables = {
                "id": user_id,
                "first": 50,
            }
            if end_cursor:
                variables["after"] = end_cursor
            
            params = {
                "query_hash": self.MEDIA_QUERY_HASH,
                "variables": json.dumps(variables)
            }
            
            try:
                resp = self.session.get(
                    self.GRAPHQL_API,
                    headers=headers,
                    params=params,
                    timeout=self.TIMEOUT
                )
                
                if resp.status_code != 200:
                    break
                    
                data = resp.json()
                media = data.get("data", {}).get("user", {}).get("edge_owner_to_timeline_media", {})
                edges = media.get("edges", [])
                
                for edge in edges:
                    ts = edge.get("node", {}).get("taken_at_timestamp")
                    if ts:
                        if oldest_timestamp is None or ts < oldest_timestamp:
                            oldest_timestamp = ts
                
                page_info = media.get("page_info", {})
                has_next = page_info.get("has_next_page", False)
                end_cursor = page_info.get("end_cursor")
                
                if not end_cursor:
                    break
                    
                time.sleep(0.3)  # Rate limit
                
            except Exception:
                break
        
        if oldest_timestamp:
            dt = datetime.fromtimestamp(oldest_timestamp)
            return dt.strftime("%Y-%m-%d %H:%M:%S"), f"~{dt.strftime('%B %Y')}"
        
        return None

    def get_web_profile(self, username: str) -> Optional[dict]:
        """Fetch profile via Instagram web API."""
        headers = {
            "User-Agent": self.WEB_USER_AGENT,
            "x-ig-app-id": self.IG_APP_ID,
            "x-requested-with": "XMLHttpRequest",
            "Accept": "*/*",
            "Accept-Language": "en-US,en;q=0.9",
            "Referer": f"https://www.instagram.com/{username}/",
        }
        
        params = {"username": username}

        try:
            resp = self.session.get(
                self.WEB_PROFILE_API,
                headers=headers,
                params=params,
                timeout=self.TIMEOUT
            )
        except requests.RequestException as e:
            print(f"   {Colors.RED}[!] Request error: {e}{Colors.RESET}")
            return None

        if resp.status_code == 200:
            try:
                return resp.json()
            except json.JSONDecodeError:
                return None
        elif resp.status_code == 404:
            print(f"   {Colors.RED}[!] User not found{Colors.RESET}")
        elif resp.status_code == 429:
            print(f"   {Colors.RED}[!] Rate limited (429){Colors.RESET}")
        else:
            print(f"   {Colors.RED}[!] HTTP {resp.status_code}{Colors.RESET}")
        
        return None

    def get_obfuscated_info(self, username: str) -> tuple[Optional[str], Optional[str]]:
        """Fetch obfuscated email/phone via Instagram mobile API."""
        headers = {"User-Agent": self.MOBILE_USER_AGENT}
        
        signed_body = '.{"login_attempt_count":"0","directly_sign_in":"true","source":"default","q":"' + username + '","ig_sig_key_version":"4"}'
        
        data = {
            "ig_sig_key_version": 4,
            "signed_body": signed_body
        }

        try:
            resp = self.session.post(
                self.LOOKUP_API,
                headers=headers,
                data=data,
                verify=False,
                timeout=self.TIMEOUT
            )
        except requests.RequestException:
            return None, None

        if resp.status_code == 200:
            try:
                res = resp.json()
                return res.get("obfuscated_email"), res.get("obfuscated_phone")
            except json.JSONDecodeError:
                pass
        
        return None, None

    def parse_profile(self, data: dict, username: str) -> Optional[InstagramProfile]:
        """Parse API response into InstagramProfile."""
        try:
            user = data.get("data", {}).get("user")
            if not user:
                return None

            profile = InstagramProfile(username=username)
            
            # Basic info
            profile.user_id = user.get("id")
            profile.full_name = user.get("full_name")
            profile.biography = user.get("biography")
            profile.external_url = user.get("external_url")
            profile.profile_pic_url = user.get("profile_pic_url_hd") or user.get("profile_pic_url")
            profile.is_private = user.get("is_private", False)
            profile.is_verified = user.get("is_verified", False)
            
            # Counts
            profile.followers = user.get("edge_followed_by", {}).get("count")
            profile.following = user.get("edge_follow", {}).get("count")
            profile.posts_count = user.get("edge_owner_to_timeline_media", {}).get("count")
            
            # Business info
            profile.is_business = user.get("is_business_account", False)
            profile.business_category = user.get("category_name")
            profile.business_email = user.get("business_email")
            profile.business_phone = user.get("business_phone_number")
            
            business_address = user.get("business_address_json")
            if business_address:
                try:
                    addr = json.loads(business_address)
                    parts = [addr.get("street_address"), addr.get("city_name"), addr.get("zip_code"), addr.get("country_code")]
                    profile.business_address = ", ".join(p for p in parts if p)
                except:
                    pass

            # Get first post date (estimate account creation)
            if not profile.is_private:
                try:
                    edges = user.get("edge_owner_to_timeline_media", {}).get("edges", [])
                    if edges:
                        # Get oldest post from the current batch
                        oldest_timestamp = None
                        for edge in edges:
                            ts = edge.get("node", {}).get("taken_at_timestamp")
                            if ts:
                                if oldest_timestamp is None or ts < oldest_timestamp:
                                    oldest_timestamp = ts
                        
                        if oldest_timestamp:
                            dt = datetime.fromtimestamp(oldest_timestamp)
                            profile.first_post_date = dt.strftime("%Y-%m-%d %H:%M:%S")
                            profile.estimated_creation = f"~{dt.strftime('%B %Y')} (based on oldest visible post)"
                except:
                    pass

            return profile
            
        except Exception as e:
            print(f"   {Colors.RED}[!] Parse error: {e}{Colors.RESET}")
            return None

    def display_profile(self, profile: InstagramProfile):
        """Display profile information."""
        url = f"https://www.instagram.com/{profile.username}"
        
        status = []
        if profile.is_private:
            status.append(f"{Colors.YELLOW}PRIVATE{Colors.RESET}")
        else:
            status.append(f"{Colors.GREEN}PUBLIC{Colors.RESET}")
        if profile.is_verified:
            status.append(f"{Colors.BLUE}VERIFIED{Colors.RESET}")
        if profile.is_business:
            status.append(f"{Colors.CYAN}BUSINESS{Colors.RESET}")
        
        print(f"\n {Colors.GREEN}[✓] Profile found:{Colors.RESET} {url}")
        print(f"   └ Status: {' | '.join(status)}")
        print()
        
        # Basic info
        print(f" {Colors.BOLD}[PROFILE]{Colors.RESET}")
        if profile.user_id:
            print(f"   ├ User ID: {profile.user_id}")
        if profile.full_name:
            print(f"   ├ Full name: {profile.full_name}")
        if profile.biography:
            bio = " ".join(profile.biography.splitlines())
            print(f"   ├ Bio: {bio}")
        if profile.external_url:
            print(f"   ├ Website: {profile.external_url}")
        if profile.profile_pic_url:
            print(f"   └ Profile pic: {profile.profile_pic_url}")
        
        # Stats
        print()
        print(f" {Colors.BOLD}[STATS]{Colors.RESET}")
        print(f"   ├ Followers: {profile.followers or 'N/A'}")
        print(f"   ├ Following: {profile.following or 'N/A'}")
        print(f"   └ Posts: {profile.posts_count or 'N/A'}")
        
        # Business info
        if profile.is_business:
            print()
            print(f" {Colors.BOLD}[BUSINESS]{Colors.RESET}")
            business_fields = [
                ("Category", profile.business_category, False),
                ("Email", profile.business_email, True),
                ("Phone", profile.business_phone, True),
                ("Address", profile.business_address, False),
            ]
            fields_to_show = [(l, v, h) for l, v, h in business_fields if v]
            
            if fields_to_show:
                for i, (label, value, highlight) in enumerate(fields_to_show):
                    prefix = "└" if i == len(fields_to_show) - 1 else "├"
                    if highlight:
                        print(f"   {prefix} {label}: {Colors.GREEN}{value}{Colors.RESET}")
                    else:
                        print(f"   {prefix} {label}: {value}")
            else:
                print(f"   └ {Colors.YELLOW}No business data available{Colors.RESET}")
        
        # Obfuscated info
        print()
        print(f" {Colors.BOLD}[OBFUSCATED]{Colors.RESET}")
        print(f"   ├ Email: {profile.obfuscated_email or 'N/A'}")
        print(f"   └ Phone: {profile.obfuscated_phone or 'N/A'}")
        
        # Account age estimation
        print()
        print(f" {Colors.BOLD}[ACCOUNT AGE]{Colors.RESET}")
        
        # Prioritize Wayback over first post
        if profile.wayback_first_archive:
            print(f"   ├ Wayback first archive: {Colors.GREEN}{profile.wayback_first_archive}{Colors.RESET}")
            print(f"   ├ Archive URL: {profile.wayback_url}")
            
            # Parse wayback date for estimated creation
            try:
                wayback_dt = datetime.strptime(profile.wayback_first_archive, "%Y-%m-%d")
                profile.estimated_creation = f"~{wayback_dt.strftime('%B %Y')} (based on Wayback archive)"
                print(f"   ├ Estimated creation: {Colors.GREEN}{profile.estimated_creation}{Colors.RESET}")
            except:
                pass
            
            # Show first post as additional info if different
            if profile.first_post_date:
                print(f"   └ First visible post: {profile.first_post_date} (may have deleted older posts)")
        elif profile.is_private:
            print(f"   ├ {Colors.YELLOW}Cannot estimate via posts (private account){Colors.RESET}")
            print(f"   └ Wayback: {Colors.YELLOW}No archives found{Colors.RESET}")
        elif profile.posts_count == 0 or profile.posts_count is None:
            print(f"   ├ {Colors.YELLOW}Cannot estimate via posts (no posts){Colors.RESET}")
            print(f"   └ Wayback: {Colors.YELLOW}No archives found{Colors.RESET}")
        elif profile.first_post_date:
            print(f"   ├ First visible post: {profile.first_post_date}")
            print(f"   ├ Estimated creation: {profile.estimated_creation}")
            print(f"   └ Wayback: {Colors.YELLOW}No archives found{Colors.RESET}")
        else:
            print(f"   ├ {Colors.YELLOW}Could not retrieve oldest post{Colors.RESET}")
            print(f"   └ Wayback: {Colors.YELLOW}No archives found{Colors.RESET}")
        
        # Trust Score
        print()
        print(f" {Colors.BOLD}[TRUST SCORE]{Colors.RESET}")
        
        # Score bar
        score = profile.trust_score
        if score >= 70:
            score_color = Colors.GREEN
            verdict = "LIKELY LEGITIMATE"
        elif score >= 40:
            score_color = Colors.YELLOW
            verdict = "MODERATE CONFIDENCE"
        else:
            score_color = Colors.RED
            verdict = "POTENTIALLY FAKE"
        
        bar_filled = int(score / 5)  # 20 chars max
        bar_empty = 20 - bar_filled
        bar = f"{'█' * bar_filled}{'░' * bar_empty}"
        
        print(f"   ┌{'─' * 22}┐")
        print(f"   │ {score_color}{bar}{Colors.RESET} │ {score_color}{score}/100{Colors.RESET}")
        print(f"   └{'─' * 22}┘")
        print(f"   {Colors.BOLD}Verdict: {score_color}{verdict}{Colors.RESET}")
        print()
        print(f"   {Colors.BOLD}Details:{Colors.RESET}")
        
        for detail, points, color in profile.trust_details:
            if color == "green":
                c = Colors.GREEN
            elif color == "yellow":
                c = Colors.YELLOW
            else:
                c = Colors.RED
            
            points_str = f"+{points}" if points > 0 else str(points) if points < 0 else "0"
            print(f"   {c}  {detail} ({points_str}){Colors.RESET}")

    def lookup(self, username: str) -> Optional[InstagramProfile]:
        """Full lookup: web profile + obfuscated info."""
        print(f"\n {Colors.CYAN}[*] Looking up: {username}{Colors.RESET}")
        print_separator()

        # Get web profile
        data = self.get_web_profile(username)
        
        if not data:
            print(f" {Colors.RED}[!] Could not fetch profile{Colors.RESET}")
            return None

        profile = self.parse_profile(data, username)
        
        if not profile:
            print(f" {Colors.RED}[!] Could not parse profile{Colors.RESET}")
            return None

        # Get obfuscated info
        time.sleep(0.5)
        obfu_email, obfu_phone = self.get_obfuscated_info(username)
        profile.obfuscated_email = obfu_email
        profile.obfuscated_phone = obfu_phone
        
        # Deep search for oldest post if account has many posts
        if not profile.is_private and profile.user_id and profile.posts_count and profile.posts_count > 12:
            print(f" {Colors.CYAN}[*] Searching for oldest post...{Colors.RESET}")
            result = self.get_oldest_post_date(profile.user_id, profile.posts_count)
            if result:
                profile.first_post_date, profile.estimated_creation = result
                profile.estimated_creation += " (based on oldest post)"

        # Check Wayback Machine
        print(f" {Colors.CYAN}[*] Checking Wayback Machine...{Colors.RESET}")
        wayback_result = self.get_wayback_first_archive(username)
        if wayback_result:
            profile.wayback_first_archive, profile.wayback_url = wayback_result

        # Calculate trust score
        print(f" {Colors.CYAN}[*] Calculating trust score...{Colors.RESET}")
        profile.trust_score, profile.trust_details = self.calculate_trust_score(profile)

        # Display
        self.display_profile(profile)

        return profile


def main():
    parser = argparse.ArgumentParser(
        description="Instagram OSINT lookup",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  %(prog)s username
  %(prog)s _aurelie_dp
        """
    )
    parser.add_argument("username", help="Instagram username to lookup")
    args = parser.parse_args()

    lookup = InstagramLookup()
    profile = lookup.lookup(args.username)
    
    print_separator()
    if profile:
        print(f" {Colors.GREEN}[✓] Lookup complete{Colors.RESET}")
    else:
        print(f" {Colors.RED}[!] Lookup failed{Colors.RESET}")


if __name__ == "__main__":
    main()
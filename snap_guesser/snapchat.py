#! /usr/bin/env python3
# -*- coding: utf-8 -*-

import sys
import argparse
import requests
import time
import traceback
from bs4 import BeautifulSoup


requests.packages.urllib3.disable_warnings(requests.packages.urllib3.exceptions.InsecureRequestWarning)


def get_postal_code(city):
    url_geocode = "http://geofree.fr/gf/zipfinder.asp"
    datas = {"todo": "2", "runok": "1", "isdom": "0", "town": "{}".format(city), "deptnb": '', "rgroup1": ''}
    req_geo = requests.post(url_geocode, data=datas, verify=False)
    soup = BeautifulSoup(req_geo.text, "html.parser")
    find_geocode = soup.find("td", {"bgcolor":"#CCCCCC"})
    if find_geocode and not "exactement" in find_geocode:
        geo_code = find_geocode.text.split(":")[1].strip()
        return(geo_code[0:2])
    else:
        return "00"



def get_snapchat(endpoint, s):
    url_snapchat = "https://www.snapchat.com/add/{}".format(endpoint)
    req_snapchat = s.get(url_snapchat, verify=False)
    if req_snapchat.status_code not in [404, 403, 401]:
        soup = BeautifulSoup(req_snapchat.text, "html.parser")
        try:
            find_name = soup.find('span', {'class': 'UserDetailsCard_title__lNhHN'})
            print(" \033[32m+ {}\033[0m snapchat username seem exit with real name {}".format(endpoint, "\033[32m{}\033[0m".format(find_name.text if find_name.text else "\033[31mNone\033[0m")))
        except AttributeError:
            print(" \033[32m+ {}\033[0m snapchat username seem exit with real name \033[31mNone\033[0m".format(endpoint))


def parse_snapchat_username(identity, pseudo, city, keyword):
    s = requests.session()

    endpoints = []

    if pseudo and not identity and not city and not keyword:
        get_snapchat(pseudo, s)
    else:
        if pseudo:
            endpoints.append(pseudo)
        if identity:
            firstname = identity.split("_")[0] if identity else None
            lastname = identity.split("_")[1] if identity else None

            bigram_lastname = "{}{}".format(lastname[0], lastname[-1])

            list_identity = [
                "{}".format(identity), 
                "{}.{}".format(firstname, lastname), "{}-{}".format(firstname, lastname), "{}{}".format(firstname, lastname),
                "{}.{}".format(lastname, firstname), "{}-{}".format(lastname, firstname), "{}{}".format(lastname, firstname),  
                "{}.{}".format(firstname, bigram_lastname), "{}-{}".format(firstname, bigram_lastname), "{}{}".format(firstname, bigram_lastname),
                "{}.{}".format(bigram_lastname, firstname), "{}-{}".format(bigram_lastname, firstname), "{}{}".format(bigram_lastname, firstname)]
            for li in list_identity:
                endpoints.append(li)
        if city and pseudo:
            postal_code = get_postal_code(city)
            list_city = [
            "{}{}".format(pseudo, city), "{}{}".format(city, pseudo), "{}_{}".format(pseudo, city), "{}.{}".format(pseudo, city),
            "{}_de{}".format(pseudo, city), "{}_of{}".format(pseudo, city), 
            "{}-de{}".format(pseudo, city), "{}-of{}".format(pseudo, city),
            "{}{}".format(pseudo, postal_code), "{}{}".format(postal_code, pseudo), "{}_{}".format(pseudo, postal_code), 
            "{}_du{}".format(pseudo, postal_code), "{}_of{}".format(pseudo, postal_code), 
            "{}-du{}".format(pseudo, postal_code), "{}-of{}".format(pseudo, postal_code) 
            ]
            for lc in list_city:
                endpoints.append(lc)
        elif city and identity:
            city_identity = []
            postal_code = get_postal_code(city)
            for e in endpoints:
                list_city_identity = [
                "{}{}".format(e, city), "{}{}".format(city, e), "{}_{}".format(e, city), "{}.{}".format(e, city),
                "{}_de{}".format(e, city), "{}_of{}".format(e, city), 
                "{}-de{}".format(e, city), "{}-of{}".format(e, city),
                "{}{}".format(e, postal_code), "{}{}".format(postal_code, e), "{}_{}".format(e, postal_code), 
                "{}_du{}".format(e, postal_code), "{}_of{}".format(e, postal_code), 
                "{}-du{}".format(e, postal_code), "{}-of{}".format(e, postal_code) ]
                for lci in list_city_identity:
                    city_identity.append(lci)
            for ci in city_identity:
                endpoints.append(ci)
        if keyword and pseudo:
            list_keyword = [
            "{}{}".format(pseudo, keyword), "{}{}".format(keyword, pseudo), 
            "{}_{}".format(pseudo, keyword), "{}-{}".format(pseudo, keyword), "{}.{}".format(pseudo, keyword)]
            for lk in list_keyword:
                endpoints.append(lk)
        elif keyword and identity:
            keyword_identity = []
            for e in endpoints:
                list_keyword_identity = [
                "{}{}".format(e, keyword), "{}{}".format(keyword, e), "{}_{}".format(e, keyword), "{}.{}".format(e, keyword),
                "{}_de{}".format(e, keyword), "{}_of{}".format(e, keyword), 
                "{}-de{}".format(e, keyword), "{}-of{}".format(e, keyword)]
                for lki in list_keyword_identity:
                    keyword_identity.append(lki)
            for ki in keyword_identity:
                endpoints.append(ki)
        for endpoint in endpoints:
            get_snapchat(endpoint, s)



if __name__ == '__main__':
    #arguments
    parser = argparse.ArgumentParser(add_help = True)
    parser = argparse.ArgumentParser(description='\033[32mcontact: https://twitter.com/c0dejump\033[0m')

    group = parser.add_argument_group('\033[34m> General\033[0m')
    group.add_argument("-i", help="Identity, exemple: -i john_doe", dest='identity', required=False)
    group.add_argument("-p", help="Pseudo, exemple: -p codejump", dest='pseudo', required=False)

    group = parser.add_argument_group('\033[34m> Assistance\033[0m')
    group.add_argument("-c", help="City adress, exemple: -c Paris", dest='city', required=False)
    group.add_argument("-k", help="Keyword, the script will be based on this, exemple: -k security; -k pro", dest='keyword', required=False)

    results = parser.parse_args()

    if len(sys.argv) < 2:
        print("\nOption missing\n")
        parser.print_help()
        sys.exit()

    identity = results.identity
    pseudo = results.pseudo
    city = results.city
    keyword = results.keyword

    parse_snapchat_username(identity, pseudo, city, keyword)
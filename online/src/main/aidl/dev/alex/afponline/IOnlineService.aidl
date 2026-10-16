package dev.alex.afponline;
interface IOnlineService {
 int apiVersion();
 String connectorCatalogue();
 String yandex(String operation, String argument);
}
